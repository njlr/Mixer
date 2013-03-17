A Bitcoin Mixing Service Using Secure Multi-party Computation
=

Nick La Rooy, 
Julian Bradfield (Supervisor)

Structure
-

report.pdf documents the design and development of the project. This is probably the best place to start. 

/bitcoinj contains a snapshot of BitcoinJ around version 0.7. It has been patched to allow more flexible signing. A compiled copy is in the lib subdirectory of /Java

/Java contains the bulk of the work. It is a series of Java programs that perform the mixing protocol given the output of the shuffling step. 

/Python contains the secure shuffling implementation. It requires that VIFF (www.viff.dk) is installed. 

/Mixer contains the compiled code and the shell scripts required to link them together. 


Running a Mix
-

Generate the VIFF configs using /Mixer/GenerateConfigs.sh

Run one instance of /Mixer/LaunchHost.sh. 

Run an instance of /Mixer/Run.sh for each participant. 
