config="player-"$player".ini"

# Generate an address, save it to the wallet
address=$(./GenerateAddress.sh $wallet)

# Perform the shuffle
numeric=$(./AddressStringToNumericString.sh $address)

shuffle="shuffle-out-"$player".txt"

#./Shuffle.sh $config $numeric $shuffle
python main.py $config $numeric $shuffle
