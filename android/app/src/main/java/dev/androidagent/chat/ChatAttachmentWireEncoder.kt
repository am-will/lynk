package dev.androidagent.chat

import org.json.JSONArray
import java.io.File
import java.util.Base64

object ChatAttachmentWireEncoder {
    fun toJsonArray(attachments: List<StoredChatAttachment>): JSONArray {
        ChatAttachmentPolicy.validateHostSend(attachments)
        return JSONArray().also { array ->
            attachments.forEach { attachment ->
                array.put(attachment.preview().toJson()
                    .put("contentBase64", Base64.getEncoder().encodeToString(File(attachment.localPath).readBytes())))
            }
        }
    }
}
