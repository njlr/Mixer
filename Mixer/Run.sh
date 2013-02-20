#amount
#player
#wallet
#java host
#java port
#host:port pairs

# Get parameters
amount=$1
player=$2
wallet=$3
hostname=$4
hostport=$5

start=6
count=$(($#-$start))

peers=${*:$start:($count+1)}

# Generate an address, save it to the wallet
address=$(./GenerateAddress.sh $wallet)
numeric=$(./AddressStringToNumericString.sh $address)

# Generate the VIFF config files
./GenerateConfig.sh $peers

config="player-"$player".ini"

# Perform the shuffle
shuffle="targets-"$player".txt"

./Shuffle.sh $config $numeric $shuffle

numerictargets=$(<$shuffle)

# Convert the result back to address strings
targets=$(./NumericStringToAddressString.sh $numerictargets)

# Run the client
echo $wallet $amount $hostname $hostport $targets
#./LaunchClient.sh $wallet $amount $hostname $hostport $targets
