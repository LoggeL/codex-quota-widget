#!/usr/bin/env node
/**
 * Emits Codex quota as widget-friendly JSON.
 * Suggested cron on HomeBox:
 *   *\/5 * * * * node /home/logge/projects/codex-quota-widget/scripts/codex-quota-status.mjs > /var/www/codex-quota/status.json.tmp && mv /var/www/codex-quota/status.json.tmp /var/www/codex-quota/status.json
 */
import { spawn } from 'node:child_process';

const TIMEOUT_MS = 10_000;

function send(proc, msg) { proc.stdin.write(JSON.stringify(msg) + '\n'); }

function fmtResetsAt(unixSec) {
  if (!unixSec) return '?';
  const diffMs = unixSec * 1000 - Date.now();
  if (diffMs <= 0) return 'soon';
  const totalMins = Math.floor(diffMs / 60_000);
  const hrs = Math.floor(totalMins / 60);
  const mins = totalMins % 60;
  const days = Math.floor(hrs / 24);
  if (days > 0) return `${days}d ${hrs % 24}h`;
  if (hrs > 0) return `${hrs}h ${mins}m`;
  return `${mins}m`;
}

async function getRateLimits() {
  const proc = spawn('codex', ['app-server'], { stdio: ['pipe', 'pipe', 'inherit'] });
  let buf = '';
  let reqId = 1;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { proc.kill(); reject(new Error('Timeout')); }, TIMEOUT_MS);
    proc.stdout.on('data', chunk => {
      buf += chunk.toString();
      const lines = buf.split('\n');
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id === 1 && msg.result !== undefined) {
          send(proc, { id: reqId++, method: 'account/rateLimits/read', params: null });
        }
        if (msg.id === 2) {
          clearTimeout(timer);
          proc.kill();
          if (msg.error) reject(new Error(msg.error.message ?? JSON.stringify(msg.error)));
          else resolve(msg.result?.rateLimits);
        }
      }
    });
    send(proc, { id: reqId++, method: 'initialize', params: { clientInfo: { name: 'codex-quota-widget', version: '0.1.0' } } });
  });
}

const rateLimits = await getRateLimits();
if (!rateLimits) throw new Error('No rate limit data');

for (const key of ['primary', 'secondary']) {
  if (rateLimits[key]) rateLimits[key].resetsIn = fmtResetsAt(rateLimits[key].resetsAt);
}

process.stdout.write(JSON.stringify({
  updatedAt: new Date().toISOString(),
  planType: rateLimits.planType ?? '',
  primary: rateLimits.primary ?? null,
  secondary: rateLimits.secondary ?? null,
  credits: rateLimits.credits ?? null,
}, null, 2) + '\n');
