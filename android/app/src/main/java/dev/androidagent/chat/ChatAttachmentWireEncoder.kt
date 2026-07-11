package dev.androidagent.chat

import org.json.JSONArray

object ChatAttachmentWireEncoder {
    fun toJsonArray(attachments: List<StoredChatAttachment>): JSONArray {
        ChatAttachmentPolicy.validateHostSend(attachments)
        return JSONArray().also { array ->
            attachments.forEach { attachment ->
                array.put(attachment.preview().toJson()
                    .put("sha256", attachment.sha256))
            }
        }
    }
}
