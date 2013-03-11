start_time=`date +%s`
java -cp Mixer.jar mixer.tools.ClientLauncher $* && echo run time is $(expr `date +%s` - $start_time) s
