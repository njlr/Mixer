import random

import viff.reactor

from twisted.internet import reactor

from viff.field import GF
from viff.runtime import gather_shares
from viff.util import dlift
from viff.util import dprint
from viff.util import find_prime



class Protocol:
    
    def __init__(self, id, address, representative):
    
        self.id = id
        self.address = long(address)
        self.representative = int(representative)
        
        # self.zp = GF(6632317036373954342534106148257945498945733337629213881042539095631298681510931689271223204739693621723707122102748364754661480466400467365295874626886952738093977819973474777946387419742641483622349146490687098645546027121030341885994312122596586105123490152878626635017562351481045703454576071680271765845737679919400085357619)
    
    
    def pair_sort(self, a, b, c):
        
        x = c * a + (1 - c) * b
        y = c * b + (1 - c) * a
        
        return x, y
    
    
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
    
        l = 32
        k = 64
        
        self.zp = GF(find_prime(2**(l + 1) + 2**(l + k + 1), blum=True))
        
        print "Shuffling... "
    
        self.runtime = runtime

        add_shares = self.runtime.input(range(1, self.runtime.num_players + 1), self.zp, self.address)
        rep_shares = self.runtime.input(range(1, self.runtime.num_players + 1), self.zp, self.representative)
        
        n = len(add_shares)
        
        for i in range(0, n): 
            
            for j in range(0, i) + range(i + 1, n): 
                
                if i != j: 
                    
                    c = rep_shares[i] < rep_shares[j]
                    
                    g, l = self.pair_sort(rep_shares[i], rep_shares[j], c)
                    
                    rep_shares[i] = l
                    rep_shares[j] = g
                    
                    p, q = self.pair_sort(add_shares[i], add_shares[j], c)
                    
                    add_shares[i] = q
                    add_shares[j] = p
        
        gather_shares(map(self.runtime.open, add_shares)).addCallback(self.done)


