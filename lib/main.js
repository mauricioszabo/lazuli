const cmds = require('./cmds')

module.exports.activate = (s) => {
  // Register subscriptions
  cmds.aux.reload()
  const disposable = cmds.aux.get_disposable()
  // Register commands
  const commands = cmds.commands()
  for(let i in commands) {
    disposable.add(
      atom.commands.add(
        'atom-workspace',
        'lazuli:' + i,
        () => commands[i]()
      )
    )
  }
}

module.exports.deactivate = (s) => { cmds.deactivate() }

module.exports.config = cmds.config
module.exports.repl = cmds.repl
module.exports.ext = cmds.repl
module.exports.autocomplete_provider = cmds.autocomplete_provider
module.exports.status_bar_consumer = cmds.status_bar_consumer
module.exports.symbol_provider = cmds.symbol_provider
