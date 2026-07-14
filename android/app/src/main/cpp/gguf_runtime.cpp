#include <jni.h>

#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <sstream>
#include <thread>
#include <vector>

#ifdef GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif

namespace {

struct GgufSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx = 0;
    bool using_gpu = false;
    std::atomic<bool> abort_flag{false};
    std::mutex mutex;
};

static std::once_flag g_backend_once;

static bool abort_callback(void* data) {
    auto* flag = reinterpret_cast<std::atomic<bool>*>(data);
    return flag != nullptr && flag->load(std::memory_order_relaxed);
}

static void throw_runtime_exception(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass clazz = env->FindClass("java/lang/RuntimeException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
        env->DeleteLocalRef(clazz);
    }
}

class JniString {
public:
    JniString(JNIEnv* env, jstring js) : env_(env), js_(js) {
        if (js_ != nullptr) {
            cstr_ = env_->GetStringUTFChars(js_, nullptr);
        }
    }
    ~JniString() {
        if (js_ != nullptr && cstr_ != nullptr) {
            env_->ReleaseStringUTFChars(js_, cstr_);
        }
    }
    const char* c_str() const { return cstr_; }
private:
    JNIEnv* env_;
    jstring js_;
    const char* cstr_ = nullptr;
};

static jstring new_string_from_utf8(JNIEnv* env, const char* utf8, size_t len) {
    if (utf8 == nullptr) return nullptr;
    if (len == 0) {
        return env->NewStringUTF("");
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(string_class);
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr == nullptr) {
        env->DeleteLocalRef(string_class);
        return nullptr;
    }
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(utf8));
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) {
        env->DeleteLocalRef(arr);
        env->DeleteLocalRef(string_class);
        return nullptr;
    }
    jstring result = static_cast<jstring>(env->NewObject(string_class, constructor, arr, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(string_class);
    return result;
}

static int utf8_leading_length(uint8_t lead) {
    if ((lead & 0x80) == 0) return 1;
    if ((lead & 0xE0) == 0xC0) return 2;
    if ((lead & 0xF0) == 0xE0) return 3;
    if ((lead & 0xF8) == 0xF0) return 4;
    return -1;
}

static void emit_complete_utf8(std::string& pending, std::string& complete) {
    while (!pending.empty()) {
        int need = utf8_leading_length(static_cast<uint8_t>(pending[0]));
        if (need < 0) {
            pending.erase(0, 1);
            continue;
        }
        if (static_cast<int>(pending.size()) < need) break;
        complete.append(pending.substr(0, need));
        pending.erase(0, need);
    }
}

static std::string apply_chat_template(const llama_model* model,
                                       const std::string& system,
                                       const std::string& user) {
    const char* model_tmpl = llama_model_chat_template(model, nullptr);

    std::vector<std::string> roles = {"system", "user"};
    std::vector<std::string> contents = {system, user};
    std::vector<llama_chat_message> messages = {
        {roles[0].c_str(), contents[0].c_str()},
        {roles[1].c_str(), contents[1].c_str()},
    };

    const char* candidates[] = {model_tmpl, nullptr};
    for (const char* tmpl : candidates) {
        std::vector<char> buf(4096);
        while (true) {
            int32_t res = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                                    buf.data(), static_cast<int32_t>(buf.size()));
            if (res < 0) break;
            if (res < static_cast<int32_t>(buf.size())) {
                return std::string(buf.data(), static_cast<size_t>(res));
            }
            buf.resize(static_cast<size_t>(res) + 1);
        }
    }

    std::ostringstream fallback;
    if (!system.empty()) {
        fallback << "system\n" << system << "\n";
    }
    fallback << "user\n" << user << "\nassistant\n";
    return fallback.str();
}

static std::vector<llama_token> tokenize_prompt(const llama_vocab* vocab,
                                                const std::string& prompt) {
    int32_t n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               nullptr, 0, true, true);
    if (n == 0) {
        return {};
    }
    if (n < 0) {
        n = -n;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    int32_t n2 = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                tokens.data(), n, true, true);
    if (n2 < 0) {
        return {};
    }
    if (n2 < n) {
        tokens.resize(static_cast<size_t>(n2));
    }
    return tokens;
}

static std::string token_to_piece(const llama_vocab* vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        int needed = -n;
        std::vector<char> v(static_cast<size_t>(needed));
        n = llama_token_to_piece(vocab, token, v.data(), needed, 0, false);
        if (n < 0) {
            return "";
        }
        return std::string(v.data(), static_cast<size_t>(n));
    }
    return std::string(buf, static_cast<size_t>(n));
}

struct SamplerGuard {
    llama_sampler* smpl = nullptr;
    ~SamplerGuard() {
        if (smpl != nullptr) {
            llama_sampler_free(smpl);
        }
    }
};

struct BatchGuard {
    llama_batch batch;
    bool active = false;
    ~BatchGuard() {
        if (active) {
            llama_batch_free(batch);
        }
    }
};

static void init_backend() {
#ifdef GGML_USE_VULKAN
    setenv("GGML_VK_DISABLE_COOPMAT", "1", 0);
    setenv("GGML_VK_DISABLE_COOPMAT2", "1", 0);
    setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 0);
    setenv("GGML_VK_DISABLE_MMVQ", "1", 0);
    setenv("GGML_VK_PREFER_HOST_MEMORY", "1", 0);
    setenv("GGML_VK_ALLOW_SYSMEM_FALLBACK", "1", 0);
#endif
    llama_backend_init();
}

static GgufSession* try_create_session(const char* path, int n_gpu_layers, int n_ctx_tokens) {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    ggml_backend_dev_t cpu_devices[] = {
        ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU),
        nullptr,
    };
    try {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = n_gpu_layers;
        mparams.use_mmap = true;
        if (n_gpu_layers == 0) {
            if (cpu_devices[0] == nullptr) return nullptr;
            mparams.devices = cpu_devices;
        }

        model = llama_model_load_from_file(path, mparams);
        if (model == nullptr) {
            return nullptr;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(std::max(n_ctx_tokens, 512));
        cparams.n_batch = static_cast<uint32_t>(std::min(2048, n_ctx_tokens));
        cparams.n_ubatch = static_cast<uint32_t>(std::min(512, n_ctx_tokens));
        if (n_gpu_layers > 0) {
            cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
            cparams.type_k = GGML_TYPE_F16;
            cparams.type_v = GGML_TYPE_F16;
            cparams.offload_kqv = true;
        } else {
            cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
            cparams.type_k = GGML_TYPE_Q4_0;
            cparams.type_v = GGML_TYPE_Q4_0;
            cparams.offload_kqv = false;
        }

        ctx = llama_init_from_model(model, cparams);
        if (ctx == nullptr) {
            llama_model_free(model);
            return nullptr;
        }

        auto* session = new GgufSession();
        session->model = model;
        session->ctx = ctx;
        session->vocab = llama_model_get_vocab(model);
        session->n_ctx = static_cast<int>(llama_n_ctx(ctx));
        session->using_gpu = n_gpu_layers > 0;
        return session;
    } catch (...) {
        if (ctx != nullptr) {
            try { llama_free(ctx); } catch (...) {}
        }
        if (model != nullptr) {
            try { llama_model_free(model); } catch (...) {}
        }
        return nullptr;
    }
}

static GgufSession* create_session(const char* path, int n_ctx_tokens, int requested_gpu_layers) {
    std::call_once(g_backend_once, init_backend);

    bool try_gpu = requested_gpu_layers > 0;
#ifdef GGML_USE_VULKAN
    if (try_gpu) {
        try {
            int vk_devices = ggml_backend_vk_get_device_count();
            if (vk_devices <= 0) {
                try_gpu = false;
            }
        } catch (...) {
            try_gpu = false;
        }
    }
#else
    try_gpu = false;
#endif

    if (try_gpu) {
        GgufSession* session = try_create_session(path, requested_gpu_layers, n_ctx_tokens);
        if (session != nullptr) return session;
    }

    return try_create_session(path, 0, n_ctx_tokens);
}

static bool check_cancellation(JNIEnv* env, jobject callback, jmethodID is_cancelled) {
    if (callback == nullptr) return false;
    jboolean result = env->CallBooleanMethod(callback, is_cancelled);
    if (env->ExceptionCheck()) return true;
    return result == JNI_TRUE;
}

static void emit_delta(JNIEnv* env, jobject callback, jmethodID on_delta,
                       const std::string& delta) {
    if (callback == nullptr || delta.empty()) return;
    jstring jdelta = new_string_from_utf8(env, delta.c_str(), delta.size());
    if (jdelta == nullptr) return;
    env->CallVoidMethod(callback, on_delta, jdelta);
    env->DeleteLocalRef(jdelta);
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_create(
        JNIEnv* env,
        jclass,
        jstring model_path,
        jint context_tokens,
        jint gpu_layers,
        jstring) {
    try {
        JniString path(env, model_path);
        if (path.c_str() == nullptr || path.c_str()[0] == '\0') {
            throw_runtime_exception(env, "model path is empty");
            return 0;
        }

        GgufSession* session = create_session(path.c_str(), static_cast<int>(context_tokens),
                                              static_cast<int>(gpu_layers));
        if (session == nullptr) {
            throw_runtime_exception(env, "failed to load GGUF model");
            return 0;
        }
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        throw_runtime_exception(env, e.what());
        return 0;
    } catch (...) {
        throw_runtime_exception(env, "failed to load GGUF model");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_close(
        JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<GgufSession*>(handle);
    if (session == nullptr) return;
    try {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->ctx != nullptr) {
            try { llama_free(session->ctx); } catch (...) {}
            session->ctx = nullptr;
        }
        if (session->model != nullptr) {
            try { llama_model_free(session->model); } catch (...) {}
            session->model = nullptr;
        }
    } catch (...) {}
    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_cancel(
        JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<GgufSession*>(handle);
    if (session == nullptr) return;
    try {
        session->abort_flag.store(true, std::memory_order_relaxed);
    } catch (...) {}
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_getContextSize(
        JNIEnv* env, jclass, jlong handle) {
    try {
        auto* session = reinterpret_cast<GgufSession*>(handle);
        return session != nullptr ? session->n_ctx : 0;
    } catch (const std::exception& e) {
        throw_runtime_exception(env, e.what());
        return 0;
    } catch (...) {
        throw_runtime_exception(env, "failed to query GGUF context size");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_getBackendName(
        JNIEnv* env, jclass, jlong handle) {
    try {
        auto* session = reinterpret_cast<GgufSession*>(handle);
        const char* name = (session != nullptr && session->using_gpu) ? "vulkan" : "cpu";
        jstring result = new_string_from_utf8(env, name, std::strlen(name));
        if (result == nullptr) {
            throw_runtime_exception(env, "failed to allocate GGUF backend name");
        }
        return result;
    } catch (const std::exception& e) {
        throw_runtime_exception(env, e.what());
        return nullptr;
    } catch (...) {
        throw_runtime_exception(env, "failed to query GGUF backend name");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_androidagent_localmodel_gguf_GgufNative_generate(
        JNIEnv* env,
        jclass,
        jlong handle,
        jstring system_prompt,
        jstring user_prompt,
        jint max_output_tokens,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jobject callback) {
    auto* session = reinterpret_cast<GgufSession*>(handle);
    if (session == nullptr) {
        throw_runtime_exception(env, "invalid GGUF session");
        return nullptr;
    }

    try {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->ctx == nullptr) {
            throw_runtime_exception(env, "GGUF session is closed");
            return nullptr;
        }

        session->abort_flag.store(false, std::memory_order_relaxed);
        llama_set_abort_callback(session->ctx, abort_callback, &session->abort_flag);
        llama_memory_clear(llama_get_memory(session->ctx), true);

        jmethodID on_delta = nullptr;
        jmethodID is_cancelled = nullptr;
        if (callback != nullptr) {
            jclass clazz = env->GetObjectClass(callback);
            if (clazz != nullptr) {
                on_delta = env->GetMethodID(clazz, "onDelta", "(Ljava/lang/String;)V");
                is_cancelled = env->GetMethodID(clazz, "isCancelled", "()Z");
                env->DeleteLocalRef(clazz);
            }
        }

        JniString system(env, system_prompt);
        JniString user(env, user_prompt);

        std::string prompt = apply_chat_template(session->model,
                                                 system.c_str() ? system.c_str() : "",
                                                 user.c_str() ? user.c_str() : "");

        std::vector<llama_token> tokens = tokenize_prompt(session->vocab, prompt);
        if (tokens.empty()) {
            throw_runtime_exception(env, "failed to tokenize prompt");
            return nullptr;
        }

        int n_ctx = session->n_ctx;
        if (static_cast<int>(tokens.size()) >= n_ctx - 1) {
            throw_runtime_exception(env, "prompt is too long for the configured context");
            return nullptr;
        }

        int n_batch = static_cast<int>(llama_n_batch(session->ctx));
        if (n_batch <= 0) n_batch = 2048;

        int n_past = 0;
        for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(n_batch)) {
            int chunk = static_cast<int>(std::min<size_t>(tokens.size() - i, static_cast<size_t>(n_batch)));
            BatchGuard batch_guard;
            batch_guard.batch = llama_batch_init(chunk, 0, 1);
            batch_guard.active = true;
            llama_batch& batch = batch_guard.batch;
            for (int j = 0; j < chunk; ++j) {
                batch.token[j] = tokens[i + j];
                batch.pos[j] = n_past + j;
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j] = 0;
            }
            batch.logits[chunk - 1] = 1;
            batch.n_tokens = chunk;
            int32_t ret = llama_decode(session->ctx, batch);
            if (ret == 2) {
                return new_string_from_utf8(env, "", 0);
            }
            if (ret != 0) {
                throw_runtime_exception(env, "prefill decode failed");
                return nullptr;
            }
            n_past += chunk;
        }

        int remaining = std::min(static_cast<int>(max_output_tokens), n_ctx - static_cast<int>(tokens.size()) - 1);
        if (remaining <= 0) remaining = 1;

        SamplerGuard sampler_guard;
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        sampler_guard.smpl = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(sampler_guard.smpl, llama_sampler_init_top_k(static_cast<int32_t>(top_k)));
        llama_sampler_chain_add(sampler_guard.smpl, llama_sampler_init_top_p(static_cast<float>(top_p), 1));
        llama_sampler_chain_add(sampler_guard.smpl, llama_sampler_init_temp(static_cast<float>(temperature)));
        llama_sampler_chain_add(sampler_guard.smpl, llama_sampler_init_dist(static_cast<uint32_t>(time(nullptr))));

        std::string output;
        std::string pending_utf8;
        llama_token id = llama_sampler_sample(sampler_guard.smpl, session->ctx, -1);

        for (int i = 0; i < remaining; ++i) {
            if (id < 0 || llama_vocab_is_eog(session->vocab, id)) break;

            llama_sampler_accept(sampler_guard.smpl, id);

            pending_utf8 += token_to_piece(session->vocab, id);
            std::string complete;
            emit_complete_utf8(pending_utf8, complete);
            if (!complete.empty()) {
                output += complete;
                emit_delta(env, callback, on_delta, complete);
                if (env->ExceptionCheck()) break;
            }

            if (check_cancellation(env, callback, is_cancelled)) break;

            BatchGuard batch_guard;
            batch_guard.batch = llama_batch_init(1, 0, 1);
            batch_guard.active = true;
            llama_batch& batch = batch_guard.batch;
            batch.token[0] = id;
            batch.pos[0] = n_past;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;
            batch.n_tokens = 1;
            int32_t ret = llama_decode(session->ctx, batch);
            n_past++;

            if (ret == 2) break;
            if (ret != 0) {
                throw_runtime_exception(env, "generation decode failed");
                return nullptr;
            }

            if (i + 1 < remaining) {
                id = llama_sampler_sample(sampler_guard.smpl, session->ctx, -1);
            }
        }

        if (!pending_utf8.empty()) {
            output += pending_utf8;
            if (callback != nullptr && !env->ExceptionCheck()) {
                emit_delta(env, callback, on_delta, pending_utf8);
            }
        }

        if (env->ExceptionCheck()) {
            return nullptr;
        }

        jstring result = new_string_from_utf8(env, output.c_str(), output.size());
        if (result == nullptr) {
            throw_runtime_exception(env, "failed to allocate generation output");
        }
        return result;
    } catch (const std::exception& e) {
        throw_runtime_exception(env, e.what());
        return nullptr;
    } catch (...) {
        throw_runtime_exception(env, "GGUF generation failed");
        return nullptr;
    }
}
