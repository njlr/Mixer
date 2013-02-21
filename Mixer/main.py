import viff.reactor

from viff.field import GF
from viff.runtime import create_runtime, gather_shares
from viff.comparison import Toft07Runtime
from viff.config import load_config
from viff.util import rand, find_prime

from optparse import OptionParser

from random import getrandbits

from twisted.internet import reactor

from protocol import Protocol



def errorHandler(failure):
    
    print "ERROR: %s" % failure


parser = OptionParser(usage="usage: %prog config address out")

options, args = parser.parse_args()

if len(args) != 3:
    
    parser.error("Wrong number of arguments! ")


parser = OptionParser()

options, args = parser.parse_args()


id, players = load_config(str(args[0]))

address = long(str(args[1]))

out = str(args[2])

protocol = Protocol(id, address, getrandbits(32), out) # This is NOT true random. Replace with SSL method after testing. 


pre_runtime = create_runtime(id, players, (len(players) - 1)//2, runtime_class=Toft07Runtime)

pre_runtime.addCallback(protocol.run)
pre_runtime.addErrback(errorHandler)


reactor.run()


