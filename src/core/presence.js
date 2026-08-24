/**
 * When we last actually spoke to a device.
 *
 * Discovery decides a peer is gone after four missed announcements. Those go
 * out over multicast and broadcast, which Wi-Fi sends at a low basic rate,
 * without acknowledgement, and drops first when the air is busy — so during a
 * large transfer the device you are transferring *to* can vanish from the list
 * while eighty gigabytes are moving between you.
 *
 * A completed TCP connection is far better evidence of presence than a
 * datagram that may never have been sent. This is where that evidence is kept.
 * It is a module-level map on purpose: the transfer code has no reason to hold
 * a reference to discovery, and discovery has no reason to know about
 * transfers.
 */
const contacts = new Map();

/** Called whenever a connection with this device succeeds, in either direction. */
export function noteContact(deviceId) {
  if (typeof deviceId === 'string' && deviceId) contacts.set(deviceId, Date.now());
}

/** Milliseconds since the epoch, or 0 if we have never reached this device. */
export function lastContact(deviceId) {
  return contacts.get(deviceId) ?? 0;
}

export function forgetContact(deviceId) {
  contacts.delete(deviceId);
}
