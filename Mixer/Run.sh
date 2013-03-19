#amount
#player
#wallet
#java host
#java port
#//host:port pairs

# Get parameters
amount=$1
player=$2
wallet=$3
hostname=$4
hostport=$5

#start=6
#count=$(($#-$start))

#peers=${*:$start:($count+1)}

# Generate configs
#./GenerateConfigs.sh $peers

# Generate an address, save it to the wallet
address=$(./GenerateAddress.sh $wallet)

# Perform the shuffle
config="player-"$player".ini"

numeric=$(./AddressStringToNumericString.sh $address)

shuffle="targets-"$player".txt"

./Shuffle.sh $config $numeric $shuffle

numerictargets=$(<$shuffle)

# Convert the result back to address strings
targets=$(./NumericStringToAddressString.sh $numerictargets)

# Run the client
./LaunchClient.sh $wallet $amount $hostname $hostport $targets
