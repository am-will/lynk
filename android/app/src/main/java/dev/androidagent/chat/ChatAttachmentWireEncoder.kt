package dev.androidagent.chat

import android.util.Base64
import org.json.JSONArray
import java.io.File

object ChatAttachmentWireEncoder {
    fun toJsonArray(attachments: List<StoredChatAttachment>): JSONArray {
        ChatAttachmentPolicy.validateHostSend(attachments)
        return JSONArray().also { array ->
            attachments.forEach { attachment ->
                array.put(attachment.preview().toJson()
                    .put("contentBase64", Base64.encodeToString(File(attachment.localPath).readBytes(), Base64.NO_WRAP)))
            }
        }
    }
}
