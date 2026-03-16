(function () {
  var bridge = window.KubeDroidBridge;
  var element = document.getElementById('terminal');

  var terminal = new Terminal({
    convertEol: true,
    cursorBlink: true,
    fontSize: 14,
    fontFamily: 'monospace',
    theme: {
      background: '#000000',
      foreground: '#E6EDF3',
      cursor: '#7EE787'
    }
  });

  function notifyResize() {
    if (!bridge || typeof bridge.onResize !== 'function') return;
    bridge.onResize(terminal.cols, terminal.rows);
  }

  terminal.open(element);
  terminal.focus();

  terminal.onData(function (data) {
    if (bridge && typeof bridge.onInput === 'function') {
      bridge.onInput(data);
    }
  });

  terminal.onResize(function () {
    notifyResize();
  });

  window.addEventListener('resize', notifyResize);
  element.addEventListener('click', function () {
    terminal.focus();
    if (bridge && typeof bridge.onTap === 'function') {
      bridge.onTap();
    }
  });

  window.KubeDroidTerminal = {
    write: function (text) {
      terminal.write(text || '');
    },
    writeError: function (text) {
      terminal.write('\u001b[31m' + (text || '') + '\u001b[0m');
    },
    clear: function () {
      terminal.clear();
    }
  };

  if (bridge && typeof bridge.onReady === 'function') {
    bridge.onReady();
  }

  notifyResize();
})();
