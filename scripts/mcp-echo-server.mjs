// Minimal MCP Streamable HTTP server for the H27 confirmation-loop E2E.
//
//   node scripts/mcp-echo-server.mjs [port]     (default 8801)
//
// Speaks exactly what McpStreamableHttpClient sends: initialize →
// notifications/initialized → tools/list → tools/call, all as plain
// application/json. Exposes one tool, `echo`.
//
// The point of this file is not the protocol — scripts/mcp_test_server.py
// already does that — it is the log: every tools/call is appended to
// scripts/mcp-echo-calls.log. QA H27's judgement is "did the tool run?", and
// that question is only answerable from the server side: the app saying "it
// did not run" is not evidence, the server never receiving the request is.
//
// The device reaches it through `adb reverse tcp:8801 tcp:8801`, so the URL
// configured in the app is http://127.0.0.1:8801/mcp and no network setup is
// needed on either side.

import { createServer } from 'node:http';
import { appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const PORT = Number(process.argv[2] || 8801);
const LOG_PATH = join(dirname(fileURLToPath(import.meta.url)), 'mcp-echo-calls.log');
const SESSION_ID = 'h27-session-001';
const STARTED_AT = Date.now();

const TOOLS = [
  {
    name: 'pong',
    description: 'Answer ping (H27 confirmation test tool)',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'ping',
    description: 'Answer pong (H27 confirmation test tool)',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'echo',
    description: 'Echo back the given message (PokeClaw H27 confirmation test tool)',
    inputSchema: {
      type: 'object',
      properties: {
        message: { type: 'string', description: 'Text to echo back' },
      },
      required: ['message'],
    },
  },
];

function logCall(tool, args) {
  const line = `${new Date().toISOString()} tools/call ${tool} ${JSON.stringify(args)}\n`;
  appendFileSync(LOG_PATH, line);
}

function sendJson(res, status, obj, session) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
    ...(session ? { 'Mcp-Session-Id': session } : {}),
  });
  res.end(body);
}

const server = createServer((req, res) => {
  if (req.method !== 'POST') {
    res.writeHead(405, { 'Content-Length': '0' });
    res.end();
    return;
  }

  let raw = '';
  req.on('data', (chunk) => {
    raw += chunk;
  });
  req.on('end', () => {
    let req_obj;
    try {
      req_obj = JSON.parse(raw || '{}');
    } catch {
      sendJson(res, 200, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } });
      return;
    }

    const method = req_obj.method || '';
    const id = req_obj.id;
    const isNotification = id === undefined || id === null;

    // Notifications get a bare 202 — the client only needs a 2xx.
    if (isNotification) {
      res.writeHead(202, { 'Content-Length': '0' });
      res.end();
      return;
    }

    switch (method) {
      case 'initialize':
        sendJson(
          res,
          200,
          {
            jsonrpc: '2.0',
            id,
            result: {
              protocolVersion: req_obj.params?.protocolVersion || '2025-03-26',
              capabilities: { tools: {} },
              serverInfo: { name: 'pokeclaw-h27-test', version: '0.1.0' },
            },
          },
          SESSION_ID
        );
        break;

      case 'tools/list':
        sendJson(res, 200, { jsonrpc: '2.0', id, result: { tools: TOOLS } });
        break;

      case 'tools/call': {
        const name = req_obj.params?.name;
        const args = req_obj.params?.arguments || {};
        logCall(name, args);
        if (name === 'pong') {
          sendJson(res, 200, {
            jsonrpc: '2.0',
            id,
            result: { content: [{ type: 'text', text: 'ping' }], isError: false },
          });
          break;
        }
        if (name === 'ping') {
          sendJson(res, 200, {
            jsonrpc: '2.0',
            id,
            result: { content: [{ type: 'text', text: 'pong' }], isError: false },
          });
          break;
        }
        if (name !== 'echo') {
          sendJson(res, 200, {
            jsonrpc: '2.0',
            id,
            result: {
              content: [{ type: 'text', text: `unknown tool: ${name}` }],
              isError: true,
            },
          });
          break;
        }
        sendJson(res, 200, {
          jsonrpc: '2.0',
          id,
          result: {
            content: [{ type: 'text', text: `echo: ${args.message ?? ''}` }],
            isError: false,
          },
        });
        break;
      }

      default:
        sendJson(res, 200, {
          jsonrpc: '2.0',
          id,
          error: { code: -32601, message: `method not found: ${method}` },
        });
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mcp-echo] listening on 127.0.0.1:${PORT}/mcp (log: ${LOG_PATH})`);
  console.log(`[mcp-echo] started ${new Date(STARTED_AT).toISOString()}`);
});
