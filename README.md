# groundtest

A memory tester circuit for Rocket Chip's memory system. The generator tile
plugs into the existing SoC generator as what looks like a CPU. However,
instead of running programs, the tile generates fixed memory requests out to
the L2. There are both cached and uncached generators. The cached generator
has an intervening L1 cache, the uncached generator sends TileLink requests
directly to the L2.

Assertions are set to fail if the wrong data comes back or if a request times
out waiting for the response.

## Configuring Rocket-Chip with groundtest

The groundtest package defines a GroundTestTile, which extends a rocket-chip Tile.
A number of Configs in rocket-chip instantiate GroundTestTile(s) in place of 
other types of Tiles, (see rocket-chip/src/main/scala/TestConfigs.scala). 

Running a ground test can be achieved in rocket-chip by:

```
cd emulator
make CONFIG=<GroundTestConfigName>
./emulator-Top-<GroundTestConfigName> <other args>
```

Currently the Configs which include GroundTestTile(s) are:

- GroundTestConfig
- MemtestConfig
- MemtestL2Config
- CacheFillTestConfig
- BroadcastRegressionTestConfig
- CacheRegressionTestConfig
- DmaTestConfig
- DmaStreamTestConfig
- NastiConverterTestConfig
- UnitTestConfig 
- TraceGenConfig 

The usual Make targets run-asm-tests and run-bmark-tests still work for these configurations, though they don't do much.

## Using TraceGenConfig

The trace generator in groundtest (tracegen.scala) has the ability to generate random memory-subsystem traces, i.e. random sequences of memory requests, along with their responses. The idea is that these traces can be validated by an external checker, such as [axe](https://github.com/CTSRD-CHERI/axe)

Putting the generator and the checker together, we can automatically search for invalid traces, i.e. possible bugs in the memory subsystem. This is useful for intensive testing, but also debugging: it is possible to search for simple failing cases.


### Quick Reference

```
(in rocket-chip)
cd emulator
make CONFIG=TraceGenConfig
./emulator-Top-TraceGenConfig +verbose -s0 |& cat > trace.log
head -n -1 trace.log > trace.trim
../scripts/toaxe.py trace.trim > trace.axe
axe check WSO trace.axe
```

### Longer Explanation

Suppose we have built the Rocket Chip emulator with the TraceGenConfig configuration as above. Running it with a few command-line options gives us a random trace:

```
  > ./emulator-TopTraceGenConfig +verbose -s0
  1: load-req     0x0000000008 #0 @64
  1: store-req  5 0x0000100008 #1 @65
  1: store-req  7 0x0000000010 #2 @66
  0: store-req  2 0x0000000008 #0 @303
  0: load-req     0x0000000008 #1 @304
  0: store-req  6 0x0000100008 #2 @305
  1: resp       0              #0 @96
  0: resp       0              #0 @350
  0: resp       2              #1 @351
  0: load-req     0x0000000010 #3 @353
  1: resp       0              #1 @149
  1: load-req     0x0000000108 #3 @152
  1: resp       0              #3 @184
  0: resp       5              #2 @422
  0: resp       0              #3 @424
  1: resp       0              #2 @226
  ...
```

Main points:

- the -s command-line option sets the random seed;
the first number on each line of the trace is the core id;
- \#N denotes a request-id N;
- \@T denotes a time T in clock cycles;
- hex numbers denote addresses;
- remaining decimal numbers denote values being loaded or stored;
- the value written by every store is unique (this simplifies trace checking and reasoning);
- this trace contains only loads, stores and responses, but the generator (and axe) also support LR/SC pairs, atomics, and fences.

We convert these traces to axe format using the toaxe.py script available in rocket-chip/scripts.
Currently we must remove the last line of the file because it cannot be parsed by the toaxe.py script.

```
  > ./emulator-Top-TraceGenConfig +verbose -s0 2>&1 | head -n -1 | ../scripts/toaxe.py -
  # &M[2] == 0x0000000010
  # &M[0] == 0x0000000008
  # &M[3] == 0x0000000108
  # &M[1] == 0x0000100008
  1: M[0] == 0 @ 64:96
  1: M[1] := 5 @ 65:
  1: M[2] := 7 @ 66:
  0: M[0] := 2 @ 303:
  0: M[0] == 2 @ 304:351
  0: M[1] := 6 @ 305:
  0: M[2] == 0 @ 353:424
  1: M[3] == 0 @ 152:184
  ...
```

Main points:

- Chisel printfs go to stdout, hence the re-direction 2>&1;
- lines begining # are comments, showing the addresses being used;
- after @ are the optional begin and end times of the operation.

Axe traces can be validated using the axe tool (must be downloaded and installed seperately):
```
  > ./emulator-Top-TraceGenConfig +verbose -s0 2>&1 | head -n -1 | ../scripts/toaxe.py - | axe check SC -
  OK
```

Axe reports that this trace is valid according to the SC model, i.e. sequential consistency.

For intensive testing, we can put the above command into a for-loop that changes the seed on each iteration.

```bash
  #!/bin/bash
  # FILE "isit"

  MODEL=$1
  for I in {1..10000}; do
    OUT=`./emulator-Top-TraceGenConfig +verbose -s$I 2>&1 | head -n -1 | ../scripts/toaxe.py - | axe check $MODEL -`
    if [ "$OUT" == "NO" ]; then
      echo Not $MODEL, seed=$I
      exit
    fi
  done
  echo Passed $I tests
```

We can now ask: is the memory-subsystem SC?

```
  > isit SC
  Not SC, seed=8
```

We can view the counter-example by running the emulator with -s8, however the resulting trace is rather long. One option is to the adjust the generator's compile-time parameters to produce smaller traces. Here, we pipe the trace through [axe-shrink](https://github.com/CTSRD-CHERI/axe/blob/master/src/axe-shrink.py) which tries to find the smallest subset of the trace the violates the given model.

```
  > ./emulator-Top-TraceGenConfig +verbose -s8 2>&1 | head -n -1 | ../scripts/toaxe.py - | axe-shrink.py SC -
  Pass 0
  Omitted 245 of 260         
  Pass 1
  Omitted 255 of 260         
  Pass 2
  Omitted 255 of 260         
  1: M[1] := 185 @ 1921:
  1: M[0] := 193 @ 1966:
  0: M[0] == 193 @ 2207:2245
  0: M[1] := 204 @ 2208:
  0: M[1] == 185 @ 2209:2269
```

One possible explanation for this behavior is that core 1 performs its writes out of order. This kind of reordering is allowed by the SPARC PSO model:

```
  > ./emulator-Top-TraceGenConfig +verbose -s8 2>&1 | head -n -1 | ../toaxe.py - | axe check PSO
  OK
```

Now we ask: is the memory-subsystem PSO?

```
  > isit PSO
  Not PSO, seed=66
```

RISC-V is expected to be WSO:
```
  > isit WSO
  Passed 10000 tests
```

This concludes the quick demo.
  

