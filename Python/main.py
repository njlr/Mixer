import viff.reactor

from viff.field import GF
from viff.runtime import create_runtime, gather_shares
from viff.config import load_config
from viff.util import rand, find_prime

from optparse import OptionParser

from twisted.internet import reactor

from protocol import Protocol



def errorHandler(failure):
    
    print "Error: %s" % failure


parser = OptionParser(usage="usage: %prog config address")

options, args = parser.parse_args()

if len(args) != 2:
    
    parser.error("wrong number of arguments")

parser = OptionParser()

options, args = parser.parse_args()


id, players = load_config(args[0])

address = args[1]


protocol = Protocol(id, address)


pre_runtime = create_runtime(id, players, 1) #(len(players) -1)//2)

pre_runtime.addCallback(protocol.run)
pre_runtime.addErrback(errorHandler)


reactor.run()


