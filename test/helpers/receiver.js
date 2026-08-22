/**
 * A bare receiver process for the tests: a TransferServer, relayed over IPC.
 * Kept separate from main.js so tests do not need discovery or the UI.
 */
import { TransferServer } from '../../src/core/server.js';

const server = new TransferServer();

server.on('offer', (transfer) => process.send({ type: 'offer', payload: transfer }));
server.on('transfer', (transfer) => process.send({ type: 'transfer', payload: transfer }));
server.on('pairing', (pairing) => process.send({ type: 'pairing', payload: pairing }));
server.on('error', (err) => process.send({ type: 'error', payload: err.message }));

process.on('message', (message) => {
  switch (message.cmd) {
    case 'respond':
      server.respond(message.transferId, message.accept);
      break;
    case 'respond-pairing':
      server.respondToPairing(message.pairingId, message.accept);
      break;
    case 'state':
      process.send({
        type: 'state',
        payload: { transfers: server.transfers, pairings: server.pairings },
      });
      break;
    case 'stop':
      server.stop();
      process.exit(0);
      break;
    default:
      break;
  }
});

await server.start(Number(process.env.PORT));
process.send({ type: 'ready' });
