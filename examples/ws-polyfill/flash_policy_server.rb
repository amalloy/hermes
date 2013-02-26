require "socket"

puts "Starting policy server..."

webserver = TCPServer.new(nil, 843)
while (session = webserver.accept)
  session.print('<cross-domain-policy><allow-access-from domain="*" to-ports="*" /></cross-domain-policy>')
  session.close
end