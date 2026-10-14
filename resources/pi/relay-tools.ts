/**
 * The tools relay offers a role, presented to pi.
 *
 * relay writes the role's tools to the JSON file named by RELAY_TOOLS_FILE:
 * a list of {name, description, parameters}, where parameters is a JSON
 * Schema. Each becomes a pi tool that runs `relay tool <name>` with the call's
 * arguments as JSON on stdin. relay decides what the call may do; this file
 * only carries it. The environment pi was started with (RELAY_DATA,
 * RELAY_ROLE, RELAY_WAKE) is passed through, and that is how relay knows
 * which role is calling.
 *
 * As a second layer, any tool call for a name not in the file is blocked.
 *
 * Two provider adjustments, both driven by relay through the environment:
 *
 * - RELAY_PI_THINKING=adaptive makes every request ask for adaptive thinking
 *   with summarized display, at effort RELAY_PI_EFFORT if set. This pi
 *   version only knows adaptive thinking for some model ids, and for others
 *   it sends a budget or "disabled", which newer models reject. Summarized
 *   display also matters: pi leaves out a thinking block with no text when
 *   it replays history, and the models that hide thinking by default send
 *   exactly that, which would make every later turn an edited history.
 * - RELAY_PROVIDER_LOG names a file where the status and headers of every
 *   failed HTTP response are appended as JSON lines, so relay can read when
 *   a rate limit lifts. pi does not pass the headers on.
 */
import { spawn } from "node:child_process";
import { appendFileSync, readFileSync } from "node:fs";

type ToolSpec = { name: string; description: string; parameters: Record<string, unknown> };

// Credentials pi needs for the model never reach a tool call.
const SECRET_VARS = ["ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN"];

function toolEnv(): NodeJS.ProcessEnv {
	const env = { ...process.env };
	for (const k of SECRET_VARS) delete env[k];
	return env;
}

function runRelayTool(bin: string, name: string, args: unknown, signal?: AbortSignal): Promise<string> {
	return new Promise((resolve, reject) => {
		const child = spawn(bin, ["tool", name], { env: toolEnv(), signal });
		let out = "";
		let err = "";
		child.stdout.on("data", (d) => (out += d));
		child.stderr.on("data", (d) => (err += d));
		child.on("error", reject);
		child.on("close", (code) => {
			const text = (out || err).trim();
			if (code === 0) resolve(text);
			else reject(new Error(text || `relay tool ${name} exited ${code}`));
		});
		child.stdin.end(JSON.stringify(args ?? {}));
	});
}

export default function relayTools(pi: any) {
	const file = process.env.RELAY_TOOLS_FILE;
	const bin = process.env.RELAY_BIN || "relay";
	const specs: ToolSpec[] = file ? JSON.parse(readFileSync(file, "utf8")) : [];
	const names = new Set(specs.map((s) => s.name));

	for (const spec of specs) {
		pi.registerTool({
			name: spec.name,
			label: spec.name,
			description: spec.description,
			parameters: spec.parameters,
			async execute(_toolCallId: string, params: unknown, signal?: AbortSignal) {
				const text = await runRelayTool(bin, spec.name, params, signal);
				return { content: [{ type: "text", text }], details: {} };
			},
		});
	}

	const thinking = process.env.RELAY_PI_THINKING;
	const effort = process.env.RELAY_PI_EFFORT;
	if (thinking === "adaptive") {
		pi.on("before_provider_request", (event: { payload: any }) => {
			const payload = { ...event.payload, thinking: { type: "adaptive", display: "summarized" } };
			delete payload.temperature;
			if (effort) payload.output_config = { ...(payload.output_config ?? {}), effort };
			return payload;
		});
	}

	// pi's after_provider_response hook never sees a failed response: the
	// SDK throws before it is called. So the failed responses are caught at
	// fetch, which the SDK looks up when it makes each client.
	const providerLog = process.env.RELAY_PROVIDER_LOG;
	if (providerLog) {
		const originalFetch = globalThis.fetch;
		globalThis.fetch = async (input: any, init?: any) => {
			const response = await originalFetch(input, init);
			if (response.status >= 400) {
				const headers: Record<string, string> = {};
				response.headers.forEach((value: string, key: string) => (headers[key] = value));
				try {
					appendFileSync(providerLog, JSON.stringify({ status: response.status, headers }) + "\n");
				} catch {}
			}
			return response;
		};
	}

	pi.on("tool_call", async (event: { toolName: string }) => {
		if (!names.has(event.toolName)) {
			return { block: true, reason: `relay offers no tool called ${event.toolName}.` };
		}
	});
}
