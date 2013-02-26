require 'rubygems'
require 'faraday'
require 'faraday_middleware'

class Hermes
  attr_reader :http, :key

  def initialize(url = 'http://localhost:2960/message')
    @http = Faraday.new(:url => url) do |faraday|
      faraday.request :json
      faraday.adapter Faraday.default_adapter
    end
  end

  def publish(topic, data={}, &block)
    data = block.call if block
    topic = "#{key}:#{topic}" if key
    http.put(topic, data)
  end

  def with_key(key)
    @key, old = key, @key
    yield
  ensure
    @key = old
  end

  def self.default(default = nil)
    @default = hermes if default
    @default ||= Hermes.new
  end

  def self.publish(*args, &block)
    default.publish(*args, &block)
  end

  def self.with_key(key, &block)
    default.with_key(key, &block)
  end
end
