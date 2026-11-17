#!/usr/bin/env node
// PreToolUse hook: blocks hard-to-reverse actions right before they run.
// Input:  Claude Code passes JSON ({ tool_name, tool_input, ... }) on stdin.
// Output: to block, exit code 2 + a reason on stderr (fed back to Claude); to allow, exit 0.
// No dependencies (Node built-ins only). When you change the rules, update block-dangerous.test.mjs too.

import { basename } from "node:path";
import { fileURLToPath } from "node:url";

// ---------- 1) Secret patterns (block if they appear in file content or commands) ----------
export const SECRET_PATTERNS = [
  { name: "Anthropic API key", re: /sk-ant-[A-Za-z0-9_-]{20,}/ },
  { name: "OpenAI API key", re: /\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}/ },
  { name: "GitHub token", re: /\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b|github_pat_[A-Za-z0-9_]{40,}/ },
  { name: "AWS access key", re: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/ },
  { name: "Stripe live key", re: /\b(?:sk|rk)_live_[A-Za-z0-9]{16,}/ },
  { name: "Slack token", re: /\bxox[abprs]-[A-Za-z0-9-]{10,}/ },
  { name: "Google API key", re: /\bAIza[0-9A-Za-z_-]{35}\b/ },
  { name: "private key (PEM)", re: /-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----/ },
];

// ---------- 2) Forbidden write paths (secrets and user data files) ----------
const FORBIDDEN_WRITE = [
  { test: (p) => /^\.env(\..+)?$/.test(basename(p)) && !/\.(example|sample)$/.test(basename(p)), why: ".env-style file (secrets)" },
  { test: (p) => /\.(pem|key|p12|pfx|jks|keystore)$/i.test(p), why: "certificate / private key file" },
  { test: (p) => /(^|\/)id_(rsa|ed25519|ecdsa)(\.pub)?$/.test(p), why: "SSH key file" },
  { test: (p) => /(^|\/)\.ard\//.test(p), why: "ard local data folder (.ard/)" },
  { test: (p) => /\.(db|sqlite3?)(-wal|-shm|-journal)?$/i.test(p), why: "local database file" },
  { test: (p) => /(^|\/)\.git\//.test(p), why: "file inside .git" },
];

// ---------- 3) Dangerous shell command patterns ----------
const RM_RECURSIVE = /\brm\s+(?:-[a-zA-Z]*\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\b|\brm\s+--recursive\b/;
const DANGEROUS_RM_TARGET = /(?:^|\s)(?:\/|\/\*|~\/?|~\/\*|\$HOME\/?|\$\{HOME\}\/?|\.\.\/?|\.\/?\*?|\*|\/(?:Users|home|etc|usr|var|bin|sbin|System|Library|Volumes)(?:\/\S*)?)(?=\s|$)/;

export const DANGEROUS_COMMANDS = [
  {
    why: "recursive delete (rm -rf) on a broad target",
    test: (c) => RM_RECURSIVE.test(c) && DANGEROUS_RM_TARGET.test(c),
  },
  {
    why: "git force push",
    test: (c) => /\bgit\s+push\b[^;&|\n]*(?:--force(?!-with-lease)\b|\s-f\b|--force-with-lease\b|\s\+\S+)/.test(c),
  },
  {
    why: "destroys git work (reset --hard / clean -fd / checkout . / restore .)",
    test: (c) =>
      /\bgit\s+reset\s+--hard\b/.test(c) ||
      /\bgit\s+clean\s+-[a-zA-Z]*f/.test(c) ||
      /\bgit\s+checkout\s+(?:--\s+)?\.(?:\s|$)/.test(c) ||
      /\bgit\s+restore\s+(?:--\S+\s+)*\.(?:\s|$)/.test(c),
  },
  {
    why: "stages secret/data files in git (git add -f, .env, .ard, DB, keys)",
    test: (c) =>
      /\bgit\s+add\b[^;&|\n]*(?:\s-f\b|--force\b)/.test(c) ||
      /\bgit\s+add\b[^;&|\n]*(?:\.env(?!\.(?:example|sample)\b)(?:\.[\w-]+)?(?=\s|$)|\.ard\b|\.(?:db|sqlite3?|pem|key)\b|id_rsa|id_ed25519)/.test(c),
  },
  {
    why: "destructive SQL (DROP / TRUNCATE / DELETE without WHERE)",
    test: (c) =>
      /\bDROP\s+(?:TABLE|DATABASE|SCHEMA)\b/i.test(c) ||
      /\bTRUNCATE\s+TABLE\b/i.test(c) ||
      /\bDELETE\s+FROM\s+\w+\s*(?:;|"|'|$)/i.test(c),
  },
  {
    why: "downloads a remote script and runs it directly (curl | sh)",
    test: (c) => /\b(?:curl|wget)\b[^|;\n]*\|\s*(?:sudo\s+)?(?:ba|z|da)?sh\b/.test(c),
  },
  {
    why: "system-wide permission/ownership change or raw disk write",
    test: (c) =>
      /\bchmod\s+-R\s+[0-7]*7[0-7]*\s+\//.test(c) ||
      /\bchown\s+-R\b[^;&|\n]*\s\/(?:\s|$)/.test(c) ||
      /\bdd\b[^;&|\n]*\bof=\/dev\//.test(c) ||
      /\bmkfs(?:\.\w+)?\b/.test(c) ||
      />\s*\/dev\/(?:sd|disk|nvme)/.test(c),
  },
  {
    why: "package publishing (npm/pnpm publish) must be run by a human",
    test: (c) => /\b(?:npm|pnpm|yarn)\s+publish\b/.test(c),
  },
  {
    why: "deletes or overwrites SQLite / event log files",
    test: (c) => /\b(?:rm|mv|truncate)\b[^;&|\n]*(?:\.ard\b|\.(?:db|sqlite3?)\b|events\.jsonl)/.test(c) || />\s*\S*(?:\.ard\/|events\.jsonl)/.test(c),
  },
];

// ---------- Evaluation ----------
function findSecret(text) {
  if (!text) return null;
  for (const { name, re } of SECRET_PATTERNS) if (re.test(text)) return name;
  return null;
}

function toRelative(p, projectDir) {
  if (!p) return "";
  const norm = String(p).replace(/\\/g, "/");
  if (projectDir && norm.startsWith(projectDir.replace(/\\/g, "/") + "/")) {
    return norm.slice(projectDir.length + 1);
  }
  return norm;
}

/** @returns {{ block: boolean, reason?: string }} */
export function evaluate(input, projectDir = process.env.CLAUDE_PROJECT_DIR ?? "") {
  const tool = input?.tool_name;
  const args = input?.tool_input ?? {};

  if (tool === "Bash") {
    const cmd = String(args.command ?? "");
    for (const rule of DANGEROUS_COMMANDS) {
      if (rule.test(cmd)) return { block: true, reason: `Dangerous command blocked: ${rule.why}` };
    }
    const secret = findSecret(cmd);
    if (secret) return { block: true, reason: `The command contains a ${secret}. Use an environment variable or the OS keychain instead.` };
    return { block: false };
  }

  if (["Write", "Edit", "MultiEdit", "NotebookEdit"].includes(tool)) {
    const path = toRelative(args.file_path ?? args.notebook_path ?? "", projectDir);
    for (const rule of FORBIDDEN_WRITE) {
      if (rule.test(path)) return { block: true, reason: `Write blocked: ${rule.why} -> ${path}` };
    }
    const content = [args.content, args.new_string, args.new_source, ...(Array.isArray(args.edits) ? args.edits.map((e) => e?.new_string) : [])]
      .filter((v) => typeof v === "string")
      .join("\n");
    const secret = findSecret(content);
    if (secret) return { block: true, reason: `Attempted to write a ${secret} directly into a file. Reference an environment variable instead (keep .env.example values empty).` };
    return { block: false };
  }

  return { block: false };
}

// ---------- Entry point ----------
async function main() {
  let raw = "";
  for await (const chunk of process.stdin) raw += chunk;
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    // If the input cannot be parsed, do not block (a broken hook must not stop development).
    process.exit(0);
  }
  const { block, reason } = evaluate(input);
  if (block) {
    process.stderr.write(`[block-dangerous] ${reason}\nThis action is forbidden from running automatically by policy. Do not work around it; ask the user to confirm.\n`);
    process.exit(2);
  }
  process.exit(0);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main();
}
