require 'rubygems'
require 'faraday'
require 'faraday_middleware'

class Hermes
  attr_reader :http, :ns

  def initialize(url = 'http://localhost:2960/message')
    @http = Faraday.new(:url => url) do |faraday|
      faraday.request :json
      faraday.adapter Faraday.default_adapter
    end
  end

  def publish(topic, data={}, &block)
    data = block.call if block
    topic = "#{ns}#{topic}" if ns
    http.put(topic, data)
  end

  def namespace(ns)
    @ns, old = ns, @ns
    yield
  ensure
    @ns = old
  end

  def self.default(default = nil)
    @default = Hermes.new(default) if default
    @default ||= Hermes.new
  end

  def self.publish(*args, &block)
    default.publish(*args, &block)
  end

  def self.namespace(ns, &block)
    default.namespace(ns, &block)
  end
end
