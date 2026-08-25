import dgram from 'node:dgram';
import os from 'node:os';
import { EventEmitter } from 'node:events';
import { lastContact } from './presence.js';
import {
  DISCOVERY_PORT, MULTICAST_ADDR, TRANSFER_PORT,
  ANNOUNCE_INTERVAL_MS, PEER_TTL_MS, PROTOCOL_VERSION,
  loadConfig, platformLabel,
} from './config.js';

// Adapters that exist but rarely carry LAN traffic: Hyper-V/WSL switches,
// VM host-only networks, VPN and mesh tunnels, Apple's peer-to-peer link.
const VIRTUAL_ADAPTER = /vethernet|virtualbox|vmware|hyper-?v|loopback|tailscale|zerotier|utun|awdl|llw|bridge|docker|veth|tun\d|tap\d/i;

function isPhysical(name) {
  return !VIRTUAL_ADAPTER.test(name);
}

/**
 * Every non-internal IPv4 address this machine owns, best-first.
 *
 * Ordering matters: it decides which address we advertise as our primary and
 * which of a peer's addresses we try to reach it on. A Windows box commonly
 * has three or four adapters and only one of them is the Wi-Fi it shares with
 * the Mac in the next room.
 */
export function localInterfaces() {
  const out = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      out.push({
        name,
        address: a.address,
        netmask: a.netmask,
        broadcast: broadcastFor(a.address, a.netmask),
        physical: isPhysical(name),
      });
    }
  }
  return out.sort((a, b) => Number(b.physical) - Number(a.physical));
}

/**
 * Choose the address of a peer we can actually reach.
 *
 * An announcement can arrive from an address that only exists inside a virtual
 * switch. Walk our own interfaces best-first and take the peer address sharing
 * a subnet with the most preferred one; fall back to the packet's source.
 */
export function pickReachableAddress(candidates, fallback) {
  const list = candidates ?? [];
  for (const local of localInterfaces()) {
    for (const candidate of list) {
      if (sameSubnet(candidate, local.address, local.netmask)) return candidate;
    }
  }
  return fallback;
}

function sameSubnet(a, b, netmask) {
  try {
    const toBits = (ip) => ip.split('.').reduce((acc, o) => (acc << 8) | Number(o), 0) >>> 0;
    const mask = toBits(netmask);
    return (toBits(a) & mask) === (toBits(b) & mask);
  } catch {
    return false;
  }
}

function broadcastFor(address, netmask) {
  try {
    const ip = address.split('.').map(Number);
    const mask = netmask.split('.').map(Number);
    return ip.map((o, i) => (o & mask[i]) | (~mask[i] & 0xff)).join('.');
  } catch {
    return '255.255.255.255';
  }
}

/**
 * Peer discovery over UDP.
 *
 * Announcements go out over multicast *and* subnet broadcast: plenty of
 * consumer Wi-Fi routers and macOS firewall setups drop one but not the other,
 * and sending both is a few hundred bytes every 3 seconds.
 */
export class Discovery extends EventEmitter {
  #socket = null;
  #timer = null;
  #peers = new Map();
  #reapTimer = null;

  constructor() {
    super();
    this.config = loadConfig();
  }

  get peers() {
    return [...this.#peers.values()];
  }

  async start() {
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.#socket = socket;

    socket.on('message', (msg, rinfo) => this.#onMessage(msg, rinfo));
    socket.on('error', (err) => this.emit('error', err));

    await new Promise((resolve, reject) => {
      socket.once('error', reject);
      socket.bind(DISCOVERY_PORT, () => {
        socket.off('error', reject);
        resolve();
      });
    });

    socket.setBroadcast(true);
    try {
      socket.setMulticastTTL(2);
      // Loopback stays on: announcements from our own host are useful when a
      // second instance runs here, and our own id is filtered out on receipt.
      socket.setMulticastLoopback(true);
    } catch { /* not fatal */ }

    for (const iface of localInterfaces()) {
      try {
        socket.addMembership(MULTICAST_ADDR, iface.address);
      } catch { /* interface may not support multicast */ }
    }

    this.#send({ t: 'probe', id: this.config.deviceId });
    this.announce();
    this.#timer = setInterval(() => this.announce(), ANNOUNCE_INTERVAL_MS);
    this.#reapTimer = setInterval(() => this.#reap(), PEER_TTL_MS / 3);
    return this;
  }

  announce() {
    this.#send(this.#announcement());
  }

  #announcement() {
    return {
      t: 'announce',
      id: this.config.deviceId,
      name: this.config.deviceName,
      os: platformLabel(),
      port: TRANSFER_PORT,
      ver: PROTOCOL_VERSION,
      addrs: localInterfaces().map((i) => i.address),
    };
  }

  /**
   * Knock on one address directly — §4.2.
   *
   * The whole discovery mechanism is multicast and broadcast, and those are
   * exactly what a guest network, a host firewall or an access point with
   * client isolation throws away. A unicast datagram to an address someone
   * typed in is the one thing that still gets through, and it is the same
   * protocol underneath: the far side answers a probe and the peer appears in
   * the list like any other.
   *
   * Both packets go, not just the probe. A probe carries only an id, so it
   * tells the far side nothing about who is asking — sending our own
   * announcement alongside means one device typing one address is enough for
   * the two of them to find each other in both directions.
   */
  reach(address) {
    if (!this.#socket || !address) return false;
    this.#sendTo({ t: 'probe', id: this.config.deviceId }, address);
    this.#sendTo(this.#announcement(), address);
    return true;
  }

  #send(payload) {
    if (!this.#socket) return;
    const buf = Buffer.from(JSON.stringify(payload));
    const targets = [MULTICAST_ADDR, ...localInterfaces().map((i) => i.broadcast)];

    // Also straight to everyone already known. Multicast and broadcast go out
    // at the lowest basic rate with no acknowledgement and are the first
    // frames an access point drops under load; a unicast datagram is rate
    // adapted and acknowledged at the link layer, so it survives exactly the
    // conditions — a transfer saturating the air — in which a peer would
    // otherwise flicker out of the list.
    for (const peer of this.#peers.values()) targets.push(peer.address);

    for (const addr of new Set(targets)) {
      this.#socket.send(buf, DISCOVERY_PORT, addr, () => { /* best effort */ });
    }
  }

  #sendTo(payload, address) {
    if (!this.#socket) return;
    const buf = Buffer.from(JSON.stringify(payload));
    this.#socket.send(buf, DISCOVERY_PORT, address, () => { /* best effort */ });
  }

  #onMessage(msg, rinfo) {
    let payload;
    try {
      payload = JSON.parse(msg.toString('utf8'));
    } catch {
      return;
    }
    if (!payload?.id || payload.id === this.config.deviceId) return;

    if (payload.t === 'probe') {
      // Answer immediately so a device that just joined sees us without waiting,
      // and answer the sender directly as well: a probe that arrived as unicast
      // came from somewhere our multicast and broadcast evidently cannot reach,
      // or it would not have needed to be typed in by hand.
      this.announce();
      this.#sendTo(this.#announcement(), rinfo.address);
      return;
    }
    if (payload.t === 'bye') {
      if (this.#peers.delete(payload.id)) this.emit('change', this.peers);
      return;
    }
    if (payload.t !== 'announce') return;

    const existing = this.#peers.get(payload.id);
    const peer = {
      id: payload.id,
      name: payload.name || rinfo.address,
      os: payload.os || 'unknown',
      address: pickReachableAddress(payload.addrs, rinfo.address),
      port: payload.port || TRANSFER_PORT,
      version: payload.ver || 1,
      lastSeen: Date.now(),
    };
    this.#peers.set(peer.id, peer);

    const changed = !existing
      || existing.name !== peer.name
      || existing.address !== peer.address
      || existing.os !== peer.os;
    if (changed) this.emit('change', this.peers);
  }

  #reap() {
    const cutoff = Date.now() - PEER_TTL_MS;
    let removed = false;
    for (const [id, peer] of this.#peers) {
      // A connection that succeeded counts as having seen the device, and
      // counts for more than an announcement that may never have arrived.
      if (Math.max(peer.lastSeen, lastContact(id)) < cutoff) {
        this.#peers.delete(id);
        removed = true;
      }
    }
    if (removed) this.emit('change', this.peers);
  }

  stop() {
    clearInterval(this.#timer);
    clearInterval(this.#reapTimer);
    if (this.#socket) {
      try { this.#send({ t: 'bye', id: this.config.deviceId }); } catch { /* ignore */ }
      try { this.#socket.close(); } catch { /* ignore */ }
      this.#socket = null;
    }
  }
}
