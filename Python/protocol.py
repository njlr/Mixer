import random

import viff.reactor

from twisted.internet import reactor

from viff.field import GF
from viff.runtime import gather_shares
from viff.runtime import ShareList
from viff.util import dlift
from viff.util import dprint
from viff.util import find_prime


def pair_sort(a, b, c):
    
    x = c * a + (1 - c) * b
    y = c * b + (1 - c) * a
    
    return x, y


class Protocol:
    
    def __init__(self, id, address, representative, out):
        
        print "Got input: " + str(address)
        
        self.id = id
        self.address = long(address)
        self.representative = long(representative)
        self.out = open(str(out), "w")
    
    
    def done(self, xs): 
        
        for x in xs: 
        
            self.out.write(str(long(x)) + " ")
        
        print "Shutting down... "
        
        self.runtime.shutdown()
    
    
    def run(self, runtime):
    
        l = 512
        k = 64
        
        print "Generating field... "
        
        self.zp = GF(find_prime(2**(l + 1) + 2**(l + k + 1), blum=True))
        self.runtime = runtime
        
        p = range(1, runtime.num_players + 1)
        
        print "Sharing values... "
        
        add_shares = runtime.input(p, self.zp, self.address)
        rep_shares = runtime.input(p, self.zp, self.representative)
        
        n = len(rep_shares)
        
        for i in range(0, n - 1):
           
            for j in range(0, n - i - 1): 
                
                c = rep_shares[j] < rep_shares[j + 1]
                
                p, q = pair_sort(rep_shares[j], rep_shares[j + 1], c)
                
                rep_shares[j] = p
                rep_shares[j + 1] = q 
                
                r, s = pair_sort(add_shares[j], add_shares[j + 1], c)
                    
                add_shares[j] = r
                add_shares[j + 1] = s
                
        
        gather_shares([ self.runtime.open(x) for x in add_shares ]).addCallback(self.done)
        
