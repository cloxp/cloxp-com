var testTimeout = 30*1000; // ms
var fs = require('fs');
var page = require('webpage').create();

page.onConsoleMessage = function(msg, lineNum, sourceId) {
  // console.log('CONSOLE: ' + msg + ' (from line #' + lineNum + ' in "' + sourceId + '")');
  console.log(msg);
};

var url = "file://" + fs.workingDirectory + "/public/run-tests.html";
page.open(url, function (status) {
  setInterval(function() {
    var result = page.evaluateJavaScript("window.cljs_tests_done");
    if (!result) return;
    var success = result.fail === 0 && result.error === 0;
    slimer.exit(success ? 0 : 1);
  }, 100);
});

setTimeout(function() {
  console.log("TIMEOUT RUNNING TESTS");
  slimer.exit(2);
}, testTimeout);