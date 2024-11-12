# What is a block?

One thing that might confuse developers is what a block is. Lazuli defines two
different terms: blocks and top-blocks.

A block is basically a "runnable" portion of your code. Suppose you have the
following pseudo-code (similar to JS):

```js
something
  .do_one_thing(param1, param2)
  .to_other_thing()
```

Imagine that you have your cursor just in the first `r` or `param1`, at the
second line. One could think that `param1` is a "valid" block, but it isn't -
you can get better results if you _select_ `param1` and evaluate it. Pulsar also
offers good ways to "expand" your selection from a single point by using the
keybind `alt-up`. So, a "block" in this case is:

```js
something
  .do_one_thing(param1, param2)
```

Please notice that this won't include `.to_other_thing`. This is why it's a
"block" - it's a "runnable piece of code" up to the point that we have up to our
cursor.

The same is true for Clojure, but in this case, a "runnable piece of code" is
more lenient - if you have some code like `(str/join "\n" [1 2 [a b]])`, both
`[a b]`, or `[1 2 [a b]]`, or even `(str/join ...)` are "runnable pieces of code
at the cursor's position" so a block, in Clojure, is the "inner
parenthesis/brackets" that we have at our cursor position.

## Top-Blocks

For Clojure, it's easy to understand what a top-block is - is the
parenthesis/brackets at "top level" - meaning that it's the same as a "block",
but "one level up" until the moment we can't go "up" anymore.

For Ruby (and future languages), it's a "patch". Suppose you have this code:

```ruby
class Person
  def hello
    puts "Hello, #@name"
  end

  def goodbye
    puts "Goodbye, #@name, nice meeting you!"
  end
end
```

Suppose you have your cursor somewhere inside `goodbye`. A "top-block", if it
was the same as Clojure, would be the _whole class definition_ but that won't be
a "patch" only on the `goodbye` method. Also, it kinda conflicts with "load
file" if you decide to have one class per file (which most codebases do). So, a
block is actually only the code required to "patch" that specific method, so:

```ruby
class Person;def goodbye
    puts "Goodbye, #@name, nice meeting you!"
  end;end
```

It might be weird to think about a "top block" as something that is,
essentially, a different code that what you have in your editor, but that's
basically the point: evaluating a top block is used to _redefine a method_ most
of the time, so that's why it is written as it is.

Also, top blocks will **not use** watch points. The reason is simple: if you
have a watch point in `goodbye` method and you use it, it would be the same as
if you're defining a **new** class _inside_ `Person` - meaning you won't be
"patching" `Person` at all.
