import random

import viff.reactor

from twisted.internet import reactor

from viff.field import GF
from viff.runtime import gather_shares
from viff.util import dlift
from viff.util import dprint
from viff.util import find_prime


def pair_sort(a, b, c):
    
    x = c * a + (1 - c) * b
    y = c * b + (1 - c) * a
    
    return x, y


def split(l, n):
    
    return [l[i:i+n] for i in range(0, len(l), n)]


class Protocol:
    
    def __init__(self, id, address, representative, out):
    
        self.id = id
        self.address = [ long(x) for x in split(address, 5) ]
        self.representative = int(representative)
        self.out = open(str(out), "w")
    
    
    def done(self, xs): 
        
        for x in xs: 
        
            self.out.write(str(long(x)) + " ")
        
        self.count += 1
        
        if (self.count == self.runtime.num_players):
            
            self.runtime.shutdown()
    
    
    def run(self, runtime):
    
        l = 104
        k = 64
        
        self.zp = GF(find_prime(2**(l + 1) + 2**(l + k + 1), blum=True))
        self.runtime = runtime
        
        p = range(1, runtime.num_players + 1)
        
        part_add_shares = [ runtime.input(p, self.zp, x) for x in self.address ]
        rep_shares = runtime.input(p, self.zp, self.representative)
        
        n = len(rep_shares)
        
        for i in range(0, n - 1):
           
            for j in range(0, n - i - 1): 
                
                c = rep_shares[j] < rep_shares[j + 1]
                
                p, q = pair_sort(rep_shares[j], rep_shares[j + 1], c)
                
                rep_shares[j] = p
                rep_shares[j + 1] = q 
                
                for add_shares in part_add_shares: 
                    
                    r, s = pair_sort(add_shares[j], add_shares[j + 1], c)
                    
                    add_shares[j] = r
                    add_shares[j + 1] = s
                
            
        self.count = 0
        
        for i in range(0, self.runtime.num_players): 
             
            self.unpack(part_add_shares, i).addCallback(self.done)
        
    
    
    def unpack(self, part_add_shares, player): 
        
        return gather_shares([ self.runtime.open(part_add_shares[x][player]) for x in range(0, len(self.address)) ])
        




