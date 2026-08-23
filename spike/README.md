# TLS 1.3 external-PSK spike

Answers the question that blocked the Android client: can a JVM TLS stack do
TLS 1.3 with an external PSK, interoperably with Node, and fast enough?

Yes. Results and consequences are in `SPEC.md` §3; the cipher-suite constraint
it uncovered is normative in `docs/PROTOCOL.md` §8.3.

Plain Java on purpose — the question was about the TLS stack, not about Kotlin
or Android, and one file runs anywhere without a build system.

## Running it

```
mkdir lib && cd lib
for a in bcprov bcutil bctls; do
  curl -LO "https://repo1.maven.org/maven2/org/bouncycastle/$a-jdk18on/1.85/$a-jdk18on-1.85.jar"
done
```

Then, from this directory:

```
node ../scripts/vectors.js
java -cp "lib/*" Spike.java crypto ../spec/vectors.json

node node-peer.mjs server 47001 <pskBase64Url> 400 &
java -cp "lib/*" Spike.java client 127.0.0.1 47001 <pskBase64Url> --aes

java -cp "lib/*" Spike.java server 47002 <pskBase64Url> &
node node-peer.mjs client 127.0.0.1 47002 <pskBase64Url> 400
```

`--aes` restricts to AES-128-GCM, which is what the Android client should use.
