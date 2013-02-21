if [ $# -lt 3 ] 
then
    
    echo "ERROR: You must supply at least three host:port arguments. "

else
    
    python generate-config-files.py $*
    
    wait
fi
