require 'rubygems'
require 'faraday'
require 'faraday_middleware'

class Hermes
  class NetworkException < StandardError
    attr_reader :cause
    def initialize(cause)
      super("#{cause.class.name}: #{cause.message}") if @cause = cause
    end
  end

  attr_reader :http

  def initialize(url = 'http://localhost:2960')
    @http = Faraday.new(:url => url) do |faraday|
      faraday.request :json
      faraday.adapter Hermes.adapter
    end
  rescue Exception => e
    raise NetworkException.new(e)
  end

  def escape_topic(topic)
    # Faraday does not like colons in your url.
    URI.escape(topic).gsub(':', '%3A')
  end

  def publish(topic, data = {}, &block)
    data = block.call if block
    topic = escape_topic(topic)
    begin
      http.put(topic, data)
    rescue Exception => e
      raise NetworkException.new(e)
    end
  end

  def self.adapter(adapter = nil)
    @adapter = adapter if adapter
    @adapter ||= Faraday::Adapter::EMSynchrony
  end
end
