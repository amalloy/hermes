require 'rubygems'
require 'faraday'
require 'faraday_middleware'

class Hermes
  attr_reader :http

  def initialize(url = 'http://localhost:2960')
    @http = Faraday.new(:url => url) do |faraday|
      faraday.request :json
      faraday.adapter Faraday.default_adapter
    end
  end

  def escape_topic(topic)
    # Faraday does not like colons in your url.
    URI.escape(topic).gsub(':', '%3A')
  end

  def publish(topic, data = {}, &block)
    data = block.call if block
    http.put(escape_topic(topic), data)
  end
end
