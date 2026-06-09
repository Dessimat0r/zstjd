# zstjd — Pure Java Zstandard (zstd) Compression

A complete, pure-Java implementation of the [Zstandard](https://facebook.github.io/zstd/) compression format. No JNI, no native dependencies.

```
Zstd.compress(data) → compressed byte[]
Zstd.decompress(compressed) → original byte[]
```

## Features

- **Full zstd format**: frames, blocks (raw/RLE/compressed), literals (raw/RLE/Huffman), sequences (FSE), checksums, content size, skippable frames
- **Huffman coding**: single-stream, 4-stream, and treeless literal compression
- **FSE entropy coding**: predefined and compressed entropy tables
- **Optimal parser**: forward-DP match selection with hash chain and lazy matching
- **Dictionary support**: load standard zstd dictionaries; compress/decompress with dict context
- **Multi-threaded compression**: virtual-thread-based parallel frame compression
- **Streaming I/O**: `ZstdInputStream` / `ZstdOutputStream` for incremental processing
- **Thread-safe**: `ThreadLocal` compressor/decompressor pools for zero-alloc reuse
- **CLI compatible**: output decodable by the reference `zstd` command-line tool

## Quick start

```java
byte[] original = "Hello, zstjd!".getBytes("UTF-8");

// Compress
byte[] compressed = Zstd.compress(original);

// Decompress
byte[] restored = Zstd.decompress(compressed);
assert Arrays.equals(original, restored);
```

### With compression level

```java
byte[] compressed = Zstd.compress(data, 3);   // default
byte[] compressed = Zstd.compress(data, 1);   // fast
byte[] compressed = Zstd.compress(data, 19);  // high compression
```

### Streaming

```java
// Compression
ByteArrayOutputStream bos = new ByteArrayOutputStream();
try (ZstdOutputStream zos = new ZstdOutputStream(bos, 3)) {
    zos.write(largeData);
}
byte[] compressed = bos.toByteArray();

// Decompression
ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
try (ZstdInputStream zis = new ZstdInputStream(bis)) {
    byte[] buf = new byte[4096];
    int n;
    while ((n = zis.read(buf)) >= 0) {
        output.write(buf, 0, n);
    }
}
```

### Parallel compression

```java
byte[] compressed = Zstd.compress(data, 3, 4); // 4 worker threads
```

### Content size hint

```java
byte[] compressed = Zstd.compress(data, 3, true); // include decompressed size
int size = Zstd.getDecompressedSize(compressed);   // -1 if unknown
```

### Direct decompress (no copy)

```java
byte[] output = new byte[knownSize];
int actualSize = Zstd.decompress(compressed, output, 0);
```

### Dictionary

```java
Dict dict = Dict.load(dictBytes);
byte[] compressed = Zstd.compress(data, 3, dict);
byte[] restored = Zstd.decompress(compressed, dict);
```

## Build

```sh
mvn compile -q        # build
mvn test               # run tests (JUnit 5, 25+ tests)
```

Requires Java 25 with `--enable-preview`.

## API

| Method | Description |
|--------|-------------|
| `Zstd.compress(byte[])` | Compress at default level (3) |
| `Zstd.compress(byte[], int level)` | Compress at specified level |
| `Zstd.compress(byte[], int level, boolean contentSize)` | Compress with optional content size |
| `Zstd.compress(byte[], int level, Dict)` | Compress with dictionary |
| `Zstd.compress(byte[], int level, boolean, Dict)` | Compress with content size + dictionary |
| `Zstd.compress(byte[], int level, int workers)` | Parallel compression |
| `Zstd.decompress(byte[])` | Decompress |
| `Zstd.decompress(byte[], byte[], int offset)` | Decompress into existing buffer |
| `Zstd.decompress(byte[], Dict)` | Decompress with dictionary |
| `Zstd.getDecompressedSize(byte[])` | Read decompressed size from frame header |
| `Zstd.compressBound(long)` | Worst-case output size |
| `Zstd.magicNumber()` | Zstd magic constant `0xFD2FB528` |
| `Dict.load(byte[])` | Load a dictionary from the standard zstd format |

## Architecture

```
org.zstjd
├── Zstd                  — Static API, ThreadLocal context pools
├── ZstdInputStream       — Streaming decompression (InputStream)
├── ZstdOutputStream      — Streaming compression (OutputStream)
├── ZstdException         — Typed exception with error codes
└── internal
    ├── Compressor        — Frame + block compression (LZ77, Huffman, FSE)
    ├── Decompressor      — Frame + block decompression
    ├── Huff              — Huffman coding (compress/decompress)
    ├── FseTable          — FSE decoding table construction
    ├── FseEncoder        — FSE encoding table construction
    ├── BitStream         — LSB-first bit writer
    ├── BitReader         — Container-based backward bit reader
    ├── ForwardReader     — Forward LSB-first bit reader
    ├── Dict              — Dictionary loading and table extraction
    ├── XXH64             — 64-bit xxhash for checksums
    └── Constants         — Tables, limits, helpers
```

## CLI compatibility

Output is decodable by the reference `zstd` CLI across all sizes (0–100K+ verified). The library uses a conservative frame header (multi-segment, checksum enabled) for maximum compatibility.

## License

BSD 2-Clause License — see [LICENSE](LICENSE)
