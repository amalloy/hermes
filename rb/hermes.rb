require 'rubygems'
require 'faraday'
require 'faraday_middleware'

class Hermes
  def initialize(url = 'http://localhost:2960/message')
    @http = Faraday.new(:url => url) do |faraday|
      faraday.request :json
      faraday.adapter Faraday.default_adapter
    end
  end

  def send(topic, data={}, &block)
    data = block.call if block
    @http.put(topic, data)
  end
end
