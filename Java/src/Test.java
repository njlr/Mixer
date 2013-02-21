import java.net.InetAddress;
import java.net.UnknownHostException;

import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.WrongNetworkException;

public class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(final String[] args) throws WrongNetworkException, AddressFormatException, UnknownHostException {
		
		System.out.println(InetAddress.getLocalHost().getHostName());
	}
}
