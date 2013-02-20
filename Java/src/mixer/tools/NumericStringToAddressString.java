package mixer.tools;

import java.io.UnsupportedEncodingException;

import mixer.AddressUtils;

public strictfp final class NumericStringToAddressString {
	
	private NumericStringToAddressString() {
		
		super();
	}
	
	public static void main(final String[] args) throws UnsupportedEncodingException {
		
		if (args.length == 0) {
			
			System.out.println("Tool for converting numeric form BTC addresses into regular form");
			System.out.println("Usage: ");
			System.out.println("Numeric String + ");
			
			return;
		}
		
		for (final String i : args) {
			
			System.out.print(AddressUtils.numericStringToAddressString(i) + " ");
		}
		
		System.out.println();
	}
}
