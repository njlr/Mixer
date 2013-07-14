A Bitcoin Mixing Service Using Secure Multi-party Computation
=

Nick La Rooy, 
Julian Bradfield (Supervisor)

Structure
-

*Report.pdf* documents the design and development of the project. This is probably the best place to start. 

*/bitcoinj* contains a snapshot of BitcoinJ (around version 0.7). It has been patched to allow more flexible signing. A compiled copy is in the *lib* subdirectory of */Java*

*/Java* contains the bulk of the work. It is a series of Java programs that perform the mixing protocol given the output of the shuffling step. 

*/Python* contains the secure shuffling implementation. Running the Python code requires that *VIFF* (<http://www.viff.dk>) is installed. 

*/Mixer* contains the compiled code and the shell scripts required to link them together. 

Disclaimer
-

The code is a proof-of-concept only; a thorough code review is required before general use. 

Running a Mix
-

Generate the VIFF configs using */Mixer/GenerateConfigs.sh*. This takes a list of host-port pairs. 

For a three party mix on localhost, use: 

```./GenerateConfigs.sh localhost:3333 localhost:4444 localhost:5555```

This must be done once for each arrangement of players. Each player *i* requires the *player-i.ini* file. 

Run one instance of */Mixer/LaunchHost.sh*. This takes: 

    0 - Network Parameters <PROD|TEST>
    1 - Amount of BTC
    2 - Port
    3 - Player Count

For a three party mix of 1 Bitcoin on TestNet, use: 

```./LaunchHost.sh TEST 1 1234 3```

Run an instance of */Mixer/Run.sh* for each participant. 

For a three party mix of 1 Bitcoin on TestNet, with the host running on *HAL9000*, use: 

```./Run.sh 1 1 test.wallet HAL9000 1234```

```./Run.sh 2 1 test2.wallet HAL9000 1234```

```./Run.sh 3 1 test3.wallet HAL9000 1234```

License
-

I would like to release this under the most permissive license possible, but there are some complications involving University of Edinburgh guidelines. 
I will update this once I find out more. 

