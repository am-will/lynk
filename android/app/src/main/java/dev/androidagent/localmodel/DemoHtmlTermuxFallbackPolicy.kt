package dev.androidagent.localmodel

import org.json.JSONObject

internal data class DemoHtmlTermuxFallback(
    val args: JSONObject,
    val targetPath: String,
    val reason: String
)

internal object DemoHtmlTermuxFallbackPolicy {
    fun replacementFor(userText: String, args: JSONObject): DemoHtmlTermuxFallback? {
        if (!isDemoFallbackEnabled()) return null
        if (!shouldReplaceTermuxCommand(userText, args)) return null
        return fallback(userText, "The model supplied an unreliable Termux command for an HTML/browser task. OpenClaw replaced it with a known-good command for the requested project.")
    }

    fun fallbackForEmptyCommand(userText: String): DemoHtmlTermuxFallback? {
        if (!isDemoFallbackEnabled()) return null
        return fallback(userText, "The model called termux_command without a command. OpenClaw supplied a concrete Termux command for the user's requested HTML project.")
    }

    private fun isDemoFallbackEnabled(): Boolean =
        java.lang.Boolean.getBoolean("openclaw.local.demoFallback")

    private fun fallback(userText: String, reason: String): DemoHtmlTermuxFallback? {
        val text = userText.lowercase()
        val isHtmlProject = listOf("html", "website", "project", "index.html").any { text.contains(it) }
        val wantsTermuxOrSharedStorage = listOf("termux", "terminal", "shell", "/sdcard/").any { text.contains(it) }
        if (!isHtmlProject || !wantsTermuxOrSharedStorage) return null

        val targetDir = targetPath(userText)
        val html = if (text.contains("calculator")) calculatorHtml() else genericHtml(userText)
        val indexPath = "$targetDir/index.html"
        val command = listOf(
            "mkdir -p ${shellQuote(targetDir)}",
            "cat > ${shellQuote(indexPath)} <<'EOF'",
            html,
            "EOF",
            "/system/bin/am start -a android.intent.action.VIEW -d ${shellQuote("file://$indexPath")} -t text/html"
        ).joinToString("\n")
        return DemoHtmlTermuxFallback(
            args = JSONObject()
                .put("command", command)
                .put("timeoutMs", 120_000L),
            targetPath = targetDir,
            reason = reason
        )
    }

    private fun shouldReplaceTermuxCommand(userText: String, args: JSONObject): Boolean {
        val user = userText.lowercase()
        val command = termuxCommandText(args).lowercase()
        val asksForCalculatorHtml = user.contains("calculator") && user.contains("html")
        val asksToOpenBrowser = user.contains("browser") || user.contains("open it") || user.contains("open the website")
        val commandOpensBrowser = command.contains("am start") || command.contains("termux-open") || command.contains("xdg-open")
        return asksForCalculatorHtml || (asksToOpenBrowser && command.isNotBlank() && !commandOpensBrowser)
    }

    private fun termuxCommandText(args: JSONObject): String =
        args.optString("command")
            .ifBlank { args.optString("cmd") }
            .ifBlank { args.optString("script") }

    private fun targetPath(userText: String): String =
        Regex("""(/sdcard/[^\s"'`]+)""")
            .find(userText)
            ?.value
            ?.trimEnd(',', '.', ';', ':', ')')
            ?: "/sdcard/Download/openclaw-project"

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun calculatorHtml(): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>OpenClaw Calculator</title>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              min-height: 100vh;
              display: grid;
              place-items: center;
              font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
              background: linear-gradient(135deg, #101827, #25324a);
              color: white;
            }
            .calculator {
              width: min(92vw, 380px);
              padding: 20px;
              border-radius: 24px;
              background: rgba(255,255,255,0.08);
              box-shadow: 0 24px 70px rgba(0,0,0,0.35);
              backdrop-filter: blur(16px);
            }
            .display {
              width: 100%;
              min-height: 82px;
              margin-bottom: 16px;
              padding: 18px;
              border: 0;
              border-radius: 18px;
              background: rgba(0,0,0,0.35);
              color: white;
              font-size: 2.1rem;
              text-align: right;
            }
            .keys {
              display: grid;
              grid-template-columns: repeat(4, 1fr);
              gap: 10px;
            }
            button {
              min-height: 62px;
              border: 0;
              border-radius: 16px;
              font-size: 1.25rem;
              color: white;
              background: rgba(255,255,255,0.14);
            }
            button:active { transform: scale(0.97); }
            .op { background: #3867ff; }
            .danger { background: #e5484d; }
            .equals { background: #26a269; grid-column: span 2; }
          </style>
        </head>
        <body>
          <main class="calculator" aria-label="Calculator">
            <input id="display" class="display" value="0" readonly>
            <section class="keys">
              <button class="danger" data-action="clear">C</button>
              <button data-action="backspace">DEL</button>
              <button data-value="%">%</button>
              <button class="op" data-value="/">/</button>
              <button data-value="7">7</button>
              <button data-value="8">8</button>
              <button data-value="9">9</button>
              <button class="op" data-value="*">*</button>
              <button data-value="4">4</button>
              <button data-value="5">5</button>
              <button data-value="6">6</button>
              <button class="op" data-value="-">-</button>
              <button data-value="1">1</button>
              <button data-value="2">2</button>
              <button data-value="3">3</button>
              <button class="op" data-value="+">+</button>
              <button data-value="0">0</button>
              <button data-value=".">.</button>
              <button class="equals" data-action="equals">=</button>
            </section>
          </main>
          <script>
            const display = document.querySelector('#display');
            let expression = '';
            function render() { display.value = expression || '0'; }
            document.querySelector('.keys').addEventListener('click', event => {
              const button = event.target.closest('button');
              if (!button) return;
              if (button.dataset.value) {
                expression += button.dataset.value;
                render();
                return;
              }
              if (button.dataset.action === 'clear') {
                expression = '';
                render();
                return;
              }
              if (button.dataset.action === 'backspace') {
                expression = expression.slice(0, -1);
                render();
                return;
              }
              if (button.dataset.action === 'equals') {
                try {
                  const result = Function('"use strict"; return (' + expression + ')')();
                  expression = Number.isFinite(result) ? String(result) : 'Error';
                } catch (_) {
                  expression = 'Error';
                }
                render();
              }
            });
          </script>
        </body>
        </html>
        """.trimIndent()

    private fun genericHtml(userText: String): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>OpenClaw Project</title>
          <style>
            body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: system-ui, sans-serif; background: #101827; color: white; }
            main { max-width: 720px; padding: 32px; }
          </style>
        </head>
        <body>
          <main>
            <h1>OpenClaw Project</h1>
            <p>${userText.take(500)}</p>
          </main>
        </body>
        </html>
        """.trimIndent()
}
