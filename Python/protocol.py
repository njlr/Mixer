import random

import viff.reactor

from twisted.internet import reactor

from viff.field import GF
from viff.runtime import gather_shares
from viff.util import dlift
from viff.util import dprint



class Protocol:
    
    def __init__(self, id, address):
    
        self.id = id
        self.address = long(address)
        
        self.zp = GF(6632317036373954342534106148257945498945733337629213881042539095631298681510931689271223204739693621723707122102748364754661480466400467365295874626886952738093977819973474777946387419742641483622349146490687098645546027121030341885994312122596586105123490152878626635017562351481045703454576071680271765845737679919400085357619)
    
    
    def done(self, xs): 
    
        print "Shuffling complete. "
        
        f = open("output.txt", "w")
        
        f.write('<?xml version="1.0" encoding="utf-8" standalone="yes"?>\n')
        f.write("<AddressList>\n")
        
        for x in xs: 
            
            f.write("\t<Address>\n")
            
            f.write("\t\t" + str(long(x)) + "\n")
            
            f.write("\t</Address>\n")
        
        f.write("</AddressList>")
        
        print "Output written to file. "
        
        self.runtime.shutdown()
    
    
    def run(self, runtime):
        
        print "Shuffling... "
    
        self.runtime = runtime

        shares = self.runtime.input(range(1, self.runtime.num_players + 1), self.zp, self.address)
        
        random_bits = [self.runtime.input(range(1, self.runtime.num_players + 1), self.zp, random.choice([0, 1])) for _ in range(len(shares)**2)]
        
        count = 0
        
        for i in range(len(shares)): 
        
            for j in range(i) + range(i + 1, len(shares)): 
                
                r = reduce(lambda x, y: x ^ y, random_bits[count])
                
                x = r * shares[i] + (1 - r) * shares[j]
                y = r * shares[j] + (1 - r) * shares[i]
                
                shares[i] = x
                shares[j] = y
                
                count += 1
        
        gather_shares(map(self.runtime.open, shares)).addCallback(self.done)








