/**
 * The Node half of the spike: the same TLS settings src/core/secure.js uses,
 * with nothing else in the way.
 *
 *   node node-peer.mjs server <port> <pskB64Url> <mib>   -- listens, then sends
 *   node node-peer.mjs client <host> <port> <pskB64Url> <mib> -- connects, then sends
 */
import tls from 'node:tls';
import crypto from 'node:crypto';

const [mode, ...rest] = process.argv.slice(2);
const PSK_IDENTITY = 'flyshare';

const tlsOptions = (psk, isServer) => (isServer
  ? { minVersion: 'TLSv1.3', pskCallback: () => psk, pskIdentityHint: PSK_IDENTITY }
  : { minVersion: 'TLSv1.3', pskCallback: () => ({ psk, identity: PSK_IDENTITY }),
      checkServerIdentity: () => undefined });

/** Push `mib` MiB down the socket, then close so the far side sees EOF. */
function blast(socket, mib) {
  const block = crypto.randomBytes(1024 * 1024);
  let sent = 0;
  const started = Date.now();
  (function write() {
    while (sent < mib) {
      sent += 1;
      if (!socket.write(block)) return socket.once('drain', write);
    }
    socket.end(() => {
      const seconds = (Date.now() - started) / 1000;
      console.log(`node sent ${mib} MiB in ${seconds.toFixed(2)}s = ${(mib / seconds).toFixed(0)} MiB/s`);
    });
  })();
}

if (mode === 'server') {
  const [port, pskB64, mib] = rest;
  const psk = Buffer.from(pskB64, 'base64url');
  const server = tls.createServer(tlsOptions(psk, true), (socket) => {
    console.log(`node server: ${socket.getProtocol()} / ${socket.getCipher().name}`);
    blast(socket, Number(mib));
    socket.on('close', () => server.close());
  });
  server.on('tlsClientError', (err) => {
    console.error('node server handshake failed:', err.message);
    process.exit(1);
  });
  server.listen(Number(port), '127.0.0.1', () => console.log(`node listening on ${port}`));
} else {
  const [host, port, pskB64, mib] = rest;
  const psk = Buffer.from(pskB64, 'base64url');
  const socket = tls.connect({ host, port: Number(port), ...tlsOptions(psk, false) }, () => {
    console.log(`node client: ${socket.getProtocol()} / ${socket.getCipher().name}`);
    blast(socket, Number(mib));
  });
  socket.on('error', (err) => {
    console.error('node client handshake failed:', err.message);
    process.exit(1);
  });
  socket.on('close', () => process.exit(0));
}
