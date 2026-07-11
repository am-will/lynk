package dev.androidagent.localmodel

import java.util.UUID

internal data class TermuxExecutionIdentity(
    val executionId: String,
    val nonce: String
) {
    init {
        require(SAFE_TOKEN.matches(executionId)) { "Invalid Termux execution id." }
        require(SAFE_TOKEN.matches(nonce)) { "Invalid Termux execution nonce." }
    }

    companion object {
        private val SAFE_TOKEN = Regex("^[a-f0-9]{32}$")

        fun create(): TermuxExecutionIdentity = TermuxExecutionIdentity(randomToken(), randomToken())

        private fun randomToken(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

internal data class TermuxRunCommandRequest(
    val commandPath: String,
    val arguments: Array<String>,
    val workdir: String,
    val label: String,
    val description: String
)

internal data class TermuxControlResult(
    val operation: String,
    val status: String,
    val executionId: String,
    val process: TermuxProcessIdentity? = null,
    val detail: String? = null
) {
    val verified: Boolean
        get() = status == "verified" || status == "armed" || status == "already-exited"
}

internal object TermuxExecutionProtocol {
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun wrappedCommand(
        identity: TermuxExecutionIdentity,
        command: String,
        workdir: String
    ): TermuxRunCommandRequest = TermuxRunCommandRequest(
        commandPath = TERMUX_BASH,
        arguments = arrayOf(
            "-c",
            RUN_WRAPPER_SCRIPT,
            "lynk-run-wrapper",
            identity.executionId,
            identity.nonce,
            command
        ),
        workdir = workdir.ifBlank { TERMUX_HOME },
        label = "Lynk tracked local command",
        description = "Runs a local command in a tracked process group so cancellation can be verified."
    )

    fun startControl(identity: TermuxExecutionIdentity): TermuxRunCommandRequest =
        controlRequest("start", identity)

    fun cancelControl(
        identity: TermuxExecutionIdentity,
        requireRunning: Boolean = false
    ): TermuxRunCommandRequest = controlRequest(if (requireRunning) "kill-running" else "cancel", identity)

    fun parseControlResult(stdout: String): TermuxControlResult? {
        val marker = stdout.lineSequence().map(String::trim).lastOrNull { it.startsWith("LYNK_") } ?: return null
        val fields = marker.split(Regex("\\s+"))
        if (fields.size < 3) return null
        val operation = when (fields[0]) {
            "LYNK_START" -> "start"
            "LYNK_KILL" -> "kill"
            else -> return null
        }
        val status = fields[1]
        val executionId = fields[2]
        if (!Regex("^[a-f0-9]{32}$").matches(executionId)) return null
        val process = if (fields.size >= 6) {
            val pid = fields[3].toLongOrNull() ?: return null
            val pgid = fields[4].toLongOrNull() ?: return null
            val startTime = fields[5].toLongOrNull() ?: return null
            TermuxProcessIdentity(pid, pgid, startTime, nonce = "")
        } else {
            null
        }
        return TermuxControlResult(
            operation = operation,
            status = status,
            executionId = executionId,
            process = process,
            detail = fields.drop(if (process == null) 3 else 6).joinToString(" ").ifBlank { null }
        )
    }

    private fun controlRequest(
        operation: String,
        identity: TermuxExecutionIdentity
    ): TermuxRunCommandRequest = TermuxRunCommandRequest(
        commandPath = TERMUX_BASH,
        arguments = arrayOf(
            "-c",
            CONTROL_SCRIPT,
            "lynk-run-control",
            operation,
            identity.executionId,
            identity.nonce
        ),
        workdir = TERMUX_HOME,
        label = "Lynk local command control",
        description = "Starts or cancels one nonce-bound tracked Lynk command."
    )

    private val RUN_WRAPPER_SCRIPT = dollarScript(
        """
        set -u
        id="__D__1"
        nonce="__D__2"
        user_command="__D__3"
        prefix=/data/data/com.termux/files/usr
        coord="__D__prefix/tmp/lynk-executions"
        state="__D__coord/__D__id.state"
        cancel="__D__coord/__D__id.cancel"
        go="__D__coord/__D__id.go"
        guard="__D__coord/__D__id.guard"

        case "__D__id:__D__nonce" in
          *[!a-f0-9:]*|*:*:*|'') exit 125 ;;
        esac
        [ "__D__{#id}" -eq 32 ] && [ "__D__{#nonce}" -eq 32 ] || exit 125
        umask 077
        mkdir -p -- "__D__coord" || exit 125

        acquire_guard() {
          local attempt=0
          while ! mkdir -- "__D__guard" 2>/dev/null; do
            attempt=__D__((attempt + 1))
            [ "__D__attempt" -lt 500 ] || return 1
            sleep 0.01
          done
        }
        release_guard() { rmdir -- "__D__guard" 2>/dev/null || true; }
        read_start_time() {
          local raw rest
          raw=__D__(cat "/proc/__D__1/stat" 2>/dev/null) || return 1
          rest="__D__{raw##*) }"
          set -- __D__rest
          [ "__D__#" -ge 20 ] || return 1
          printf '%s' "__D__{20}"
        }
        cleanup() {
          acquire_guard || return 0
          rm -f -- "__D__state" "__D__go" "__D__cancel"
          release_guard
        }

        acquire_guard || exit 125
        if [ -f "__D__cancel" ] && [ "__D__(cat "__D__cancel" 2>/dev/null)" = "__D__nonce" ]; then
          rm -f -- "__D__cancel"
          release_guard
          exit 143
        fi

        "__D__prefix/bin/setsid" "__D__prefix/bin/bash" -c \
          'kill -STOP "$$"; exec "$1" -lc "$2"' \
          lynk-child "__D__prefix/bin/bash" "__D__user_command" &
        pid=__D__!

        stopped=false
        attempt=0
        while [ "__D__attempt" -lt 500 ]; do
          raw=__D__(cat "/proc/__D__pid/stat" 2>/dev/null) || break
          rest="__D__{raw##*) }"
          set -- __D__rest
          if [ "__D__{1:-}" = T ]; then stopped=true; break; fi
          attempt=__D__((attempt + 1))
          sleep 0.01
        done
        if [ "__D__stopped" != true ]; then
          kill -KILL "__D__pid" 2>/dev/null || true
          release_guard
          wait "__D__pid" 2>/dev/null || true
          exit 125
        fi

        pgid=__D__("__D__prefix/bin/ps" -o pgid= -p "__D__pid" 2>/dev/null | tr -d ' ')
        start=__D__(read_start_time "__D__pid") || {
          kill -KILL "__D__pid" 2>/dev/null || true
          release_guard
          wait "__D__pid" 2>/dev/null || true
          exit 125
        }
        [ "__D__pgid" = "__D__pid" ] || {
          kill -KILL "__D__pid" 2>/dev/null || true
          release_guard
          wait "__D__pid" 2>/dev/null || true
          exit 125
        }
        tmp="__D__state.__D____D__"
        printf '%s %s %s %s\n' "__D__nonce" "__D__pid" "__D__pgid" "__D__start" > "__D__tmp"
        mv -f -- "__D__tmp" "__D__state"
        release_guard

        action=
        attempt=0
        while [ "__D__attempt" -lt 3000 ]; do
          if [ -f "__D__cancel" ] && [ "__D__(cat "__D__cancel" 2>/dev/null)" = "__D__nonce" ]; then
            action=cancel
            break
          fi
          if [ -f "__D__go" ] && [ "__D__(cat "__D__go" 2>/dev/null)" = "__D__nonce" ]; then
            action=go
            break
          fi
          kill -0 "__D__pid" 2>/dev/null || break
          attempt=__D__((attempt + 1))
          sleep 0.01
        done

        if [ "__D__action" != go ]; then
          kill -KILL -- "-__D__pgid" 2>/dev/null || true
          wait "__D__pid" 2>/dev/null || true
          cleanup
          exit 143
        fi
        rm -f -- "__D__go"
        kill -CONT -- "-__D__pgid" 2>/dev/null || true
        wait "__D__pid"
        code=__D__?
        cleanup
        exit "__D__code"
        """.trimIndent()
    )

    private val CONTROL_SCRIPT = dollarScript(
        """
        set -u
        operation="__D__1"
        id="__D__2"
        nonce="__D__3"
        prefix=/data/data/com.termux/files/usr
        coord="__D__prefix/tmp/lynk-executions"
        state="__D__coord/__D__id.state"
        cancel="__D__coord/__D__id.cancel"
        go="__D__coord/__D__id.go"
        guard="__D__coord/__D__id.guard"

        case "__D__operation:__D__id:__D__nonce" in
          *[!a-z0-9:-]*|*:*:*:*|'') exit 125 ;;
        esac
        [ "__D__{#id}" -eq 32 ] && [ "__D__{#nonce}" -eq 32 ] || exit 125
        case "__D__operation" in start|cancel|kill-running) ;; *) exit 125 ;; esac
        umask 077
        mkdir -p -- "__D__coord" || exit 125

        acquire_guard() {
          local attempt=0
          while ! mkdir -- "__D__guard" 2>/dev/null; do
            attempt=__D__((attempt + 1))
            [ "__D__attempt" -lt 500 ] || return 1
            sleep 0.01
          done
        }
        release_guard() { rmdir -- "__D__guard" 2>/dev/null || true; }
        read_start_time() {
          local raw rest
          raw=__D__(cat "/proc/__D__1/stat" 2>/dev/null) || return 1
          rest="__D__{raw##*) }"
          set -- __D__rest
          [ "__D__#" -ge 20 ] || return 1
          printf '%s' "__D__{20}"
        }
        wait_for_state() {
          local attempt=0
          while [ ! -f "__D__state" ]; do
            [ "__D__attempt" -lt 500 ] || return 1
            attempt=__D__((attempt + 1))
            sleep 0.01
          done
        }
        load_and_verify() {
          read -r stored_nonce pid pgid start < "__D__state" || return 1
          [ "__D__stored_nonce" = "__D__nonce" ] || return 1
          case "__D__pid:__D__pgid:__D__start" in *[!0-9:]*) return 1 ;; esac
          [ "__D__pid" = "__D__pgid" ] || return 1
          current_start=__D__(read_start_time "__D__pid") || return 2
          current_pgid=__D__("__D__prefix/bin/ps" -o pgid= -p "__D__pid" 2>/dev/null | tr -d ' ')
          [ "__D__current_start" = "__D__start" ] && [ "__D__current_pgid" = "__D__pgid" ] || return 1
        }

        if [ "__D__operation" = start ]; then
          wait_for_state || { printf 'LYNK_START failed %s state-timeout\n' "__D__id"; exit 75; }
          acquire_guard || { printf 'LYNK_START failed %s guard-timeout\n' "__D__id"; exit 75; }
          if [ -f "__D__cancel" ]; then
            release_guard
            printf 'LYNK_START failed %s cancellation-pending\n' "__D__id"
            exit 75
          fi
          load_and_verify
          verify_code=__D__?
          if [ "__D__verify_code" -ne 0 ]; then
            release_guard
            printf 'LYNK_START failed %s identity-mismatch\n' "__D__id"
            exit 75
          fi
          tmp="__D__go.__D____D__"
          printf '%s\n' "__D__nonce" > "__D__tmp"
          mv -f -- "__D__tmp" "__D__go"
          release_guard
          printf 'LYNK_START verified %s %s %s %s\n' "__D__id" "__D__pid" "__D__pgid" "__D__start"
          exit 0
        fi

        if [ "__D__operation" = kill-running ]; then
          wait_for_state || { printf 'LYNK_KILL failed %s state-timeout\n' "__D__id"; exit 75; }
        fi
        acquire_guard || { printf 'LYNK_KILL failed %s guard-timeout\n' "__D__id"; exit 75; }
        tmp="__D__cancel.__D____D__"
        printf '%s\n' "__D__nonce" > "__D__tmp"
        mv -f -- "__D__tmp" "__D__cancel"
        if [ ! -f "__D__state" ]; then
          release_guard
          printf 'LYNK_KILL armed %s\n' "__D__id"
          exit 0
        fi
        load_and_verify
        verify_code=__D__?
        if [ "__D__verify_code" -eq 2 ]; then
          rm -f -- "__D__state" "__D__go" "__D__cancel"
          release_guard
          printf 'LYNK_KILL already-exited %s\n' "__D__id"
          exit 0
        fi
        if [ "__D__verify_code" -ne 0 ]; then
          release_guard
          printf 'LYNK_KILL failed %s identity-mismatch\n' "__D__id"
          exit 75
        fi
        kill -KILL -- "-__D__pgid" 2>/dev/null || true
        release_guard

        attempt=0
        while kill -0 -- "-__D__pgid" 2>/dev/null; do
          [ "__D__attempt" -lt 500 ] || {
            printf 'LYNK_KILL failed %s group-still-alive\n' "__D__id"
            exit 75
          }
          attempt=__D__((attempt + 1))
          sleep 0.01
        done
        acquire_guard || true
        rm -f -- "__D__state" "__D__go" "__D__cancel"
        release_guard
        printf 'LYNK_KILL verified %s %s %s %s\n' "__D__id" "__D__pid" "__D__pgid" "__D__start"
        exit 0
        """.trimIndent()
    )

    private fun dollarScript(script: String): String = script.replace("__D__", "\$")
}
