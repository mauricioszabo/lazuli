require "nrepl-lazuli"

class Person
  def initialize(name, age)
    @name = name
    @age = age
  end

  def hello(greeting)
    "#{greeting}, #{@name}"
  end

  def adult?(country)
    if country == :japan
      @age >= 20
    else
      @age >= 18
    end
  end
end

NREPL::Server.start(port: 9192)
