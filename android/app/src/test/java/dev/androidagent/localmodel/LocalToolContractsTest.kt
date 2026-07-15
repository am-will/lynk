package dev.androidagent.localmodel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolContractsTest {
    @Test
    fun acceptsBoundedReadOnlyCalls() {
        assertValid("local_list_files", JSONObject())
        assertValid("local_read_file", JSONObject().put("path", "notes.txt"))
        assertValid("local_search_files", JSONObject().put("query", "TODO").put("path", "."))
        assertValid("phone_wait", JSONObject().put("ms", 500))
    }

    @Test
    fun rejectsUnknownToolsAndExtraFields() {
        assertInvalid("invented_tool", JSONObject())
        assertInvalid("local_read_file", JSONObject().put("path", "notes.txt").put("unexpected", true))
        assertInvalid("phone_observe", JSONObject().put("ignored", "value"))
    }

    @Test
    fun rejectsWrongTypesRangesAndOversizedValues() {
        assertInvalid("phone_wait", JSONObject().put("ms", "500"))
        assertInvalid("phone_wait", JSONObject().put("ms", 120_001))
        assertInvalid("phone_tap_normalized", JSONObject()
            .put("xPct", 1.1).put("yPct", 0.5).put("approvalCapability", "a".repeat(20)))
        assertInvalid("local_search_files", JSONObject().put("query", "x".repeat(501)))
        assertInvalid("termux_command", JSONObject()
            .put("command", "x".repeat(20_001)).put("approvalCapability", "a".repeat(20)))
    }

    @Test
    fun writeAndTermuxRequireCapabilitiesOutsideApprovalTargets() {
        assertInvalid("local_write_file", JSONObject().put("path", "x.txt").put("text", "hello"))
        assertInvalid("termux_command", JSONObject().put("command", "echo hello"))
        assertValid("local_write_file", JSONObject()
            .put("path", "x.txt").put("text", "hello").put("approvalCapability", "a".repeat(20)))
        assertValid("termux_command", JSONObject()
            .put("command", "echo hello").put("approvalCapability", "a".repeat(20)))
    }

    @Test
    fun directSensitivePhoneActionsStillRequireApprovalCapabilities() {
        val tapArgs = JSONObject().put("x", 100).put("y", 200)

        assertInvalid("phone_tap_xy", tapArgs)
        assertValid("phone_tap_xy", JSONObject(tapArgs.toString())
            .put("approvalCapability", "a".repeat(20)))
    }

    @Test
    fun confirmationValidatesItsExactNestedTarget() {
        assertValid("phone_ask_user_confirmation", JSONObject()
            .put("command", "local_write_file")
            .put("args", JSONObject().put("path", "x.txt").put("text", "hello")))
        assertInvalid("phone_ask_user_confirmation", JSONObject()
            .put("command", "local_write_file")
            .put("args", JSONObject().put("path", "x.txt").put("text", "hello").put("extra", true)))
    }

    private fun assertValid(name: String, args: JSONObject) {
        assertTrue("expected valid $name", LocalToolContracts.validate(LocalToolCall(name, args)) is LocalToolValidation.Valid)
    }

    private fun assertInvalid(name: String, args: JSONObject) {
        assertTrue("expected invalid $name", LocalToolContracts.validate(LocalToolCall(name, args)) is LocalToolValidation.Invalid)
    }
}
