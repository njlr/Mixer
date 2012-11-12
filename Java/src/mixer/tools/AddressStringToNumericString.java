package mixer.tools;

import mixer.Utils;

public class AddressStringToNumericString {
	
	public static void main(String[] args) {
		
		if (args.length == 1) {
			
			String addressString = args[0];
			
			String numericString = Utils.addressStringToNumericString(addressString);
			
			System.out.println(numericString);
		}
		else {
			
			System.out.println("Tool for converting BTC addresses into numeric form");
			System.out.println("Usage: ");
			System.out.println("0 - Address String");
		}
	}
}
