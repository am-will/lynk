import { PhoneToolClient, screenshotDirectory } from "../mcp/phoneToolClient.js";
import { phoneCommandSchema } from "../protocol/messages.js";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { ensurePrivateDirectory } from "../host/PrivatePersistence.js";

const command = phoneCommandSchema.parse(process.argv[2]);
const args = process.argv[3] ? JSON.parse(process.argv[3]) : {};
const result = await new PhoneToolClient().command(command, args, 30_000);
if (result.screenshotBase64) {
  const dir = screenshotDirectory();
  await ensurePrivateDirectory(dir);
  const screenshotPath = join(dir, `phone-${Date.now()}.png`);
  await writeFile(screenshotPath, Buffer.from(result.screenshotBase64, "base64"), { mode: 0o600 });
  console.log(JSON.stringify({ ...result, screenshotBase64: undefined, screenshotPath }, null, 2));
} else {
  console.log(JSON.stringify(result, null, 2));
}
