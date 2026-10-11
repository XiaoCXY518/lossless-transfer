package com.losslesstransfer.net

const val FILES_PLACEHOLDER = "__FILES__"

const val RECEIVE_PAGE_HTML: String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>无损传输</title>
<style>
 *{box-sizing:border-box}
 body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;margin:0;padding:16px;background:#f5f6f8;color:#222}
 h1{font-size:19px;margin:0 0 12px}
 .card{background:#fff;border-radius:10px;padding:14px;margin-bottom:14px;box-shadow:0 1px 4px rgba(0,0,0,.08)}
 #drop{border:2px dashed #b9c2cf;border-radius:10px;padding:28px 12px;text-align:center;color:#6b7280;cursor:pointer;transition:.2s}
 #drop.over{border-color:#3b82f6;background:#eff6ff;color:#2563eb}
 button{border:0;border-radius:8px;padding:9px 14px;margin:6px 6px 0 0;background:#3b82f6;color:#fff;font-size:14px;cursor:pointer}
 button.gray{background:#e5e7eb;color:#374151}
 ul{list-style:none;padding:0;margin:0;max-height:340px;overflow:auto}
 li{display:flex;justify-content:space-between;gap:10px;padding:8px 4px;border-bottom:1px solid #eee;font-size:14px}
 li:last-child{border-bottom:0}
 li a{color:#2563eb;text-decoration:none;word-break:break-all}
 li span{color:#9ca3af;white-space:nowrap}
 #log{font-size:13px;color:#374151;margin-top:10px;white-space:pre-wrap;max-height:170px;overflow:auto}
</style>
</head>
<body>
<h1>无损传输 · 接收端</h1>
<div class="card">
  <div id="drop">把文件 / 文件夹拖到这里，或点击选择</div>
  <div>
    <button id="btnFiles">选择文件</button>
    <button id="btnDir" class="gray">选择文件夹</button>
  </div>
  <input type="file" id="f" multiple hidden>
  <input type="file" id="d" webkitdirectory hidden>
  <div id="log"></div>
</div>
<div class="card">
  <h1 style="font-size:16px">已接收文件</h1>
  <ul>__FILES__</ul>
</div>
<script>
(function(){
  var drop = document.getElementById('drop');
  var logBox = document.getElementById('log');
  function log(t) {
    logBox.textContent += t + '\n';
    logBox.scrollTop = logBox.scrollHeight;
  }
  async function uploadPairs(pairs) {
    if (!pairs.length) { log('没有可上传的内容'); return; }
    var ok = 0;
    for (var i = 0; i < pairs.length; i++) {
      var file = pairs[i][0];
      var rel = pairs[i][1];
      try {
        log('上传中: ' + rel + '  (' + (file.size / 1048576).toFixed(2) + ' MB)');
        var resp = await fetch('/upload?path=' + encodeURIComponent(rel), { method: 'PUT', body: file });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        ok++;
        log('完成: ' + rel);
      } catch (e) {
        log('失败: ' + rel + ' -> ' + e.message);
      }
    }
    log('—— 全部结束，成功 ' + ok + '/' + pairs.length + ' ——');
  }
  function walkEntry(entry, prefix, out) {
    return new Promise(function(resolve) {
      if (entry.isFile) {
        entry.file(function(f) { out.push([f, prefix + entry.name]); resolve(); },
                   function() { resolve(); });
      } else if (entry.isDirectory) {
        var reader = entry.createReader();
        var all = [];
        function readMore() {
          reader.readEntries(function(ents) {
            if (ents.length === 0) {
              var i = 0;
              function next() {
                if (i >= all.length) { resolve(); return; }
                walkEntry(all[i], prefix + entry.name + '/', out).then(function() { i++; next(); });
              }
              next();
              return;
            }
            for (var j = 0; j < ents.length; j++) all.push(ents[j]);
            readMore();
          }, function() { resolve(); });
        }
        readMore();
      } else {
        resolve();
      }
    });
  }
  document.getElementById('btnFiles').addEventListener('click', function() {
    document.getElementById('f').click();
  });
  document.getElementById('btnDir').addEventListener('click', function() {
    document.getElementById('d').click();
  });
  drop.addEventListener('click', function() {
    document.getElementById('f').click();
  });
  document.getElementById('f').addEventListener('change', function(e) {
    var files = Array.prototype.slice.call(e.target.files);
    uploadPairs(files.map(function(f) { return [f, f.name]; }));
    e.target.value = '';
  });
  document.getElementById('d').addEventListener('change', function(e) {
    var files = Array.prototype.slice.call(e.target.files);
    uploadPairs(files.map(function(f) { return [f, f.webkitRelativePath || f.name]; }));
    e.target.value = '';
  });
  ['dragenter', 'dragover'].forEach(function(ev) {
    drop.addEventListener(ev, function(e) { e.preventDefault(); drop.classList.add('over'); });
  });
  ['dragleave', 'drop'].forEach(function(ev) {
    drop.addEventListener(ev, function(e) { e.preventDefault(); drop.classList.remove('over'); });
  });
  drop.addEventListener('drop', async function(e) {
    var items = e.dataTransfer.items;
    var pairs = [];
    if (items && items.length && items[0].webkitGetAsEntry) {
      var entries = [];
      for (var i = 0; i < items.length; i++) {
        var en = items[i].webkitGetAsEntry();
        if (en) entries.push(en);
      }
      for (var j = 0; j < entries.length; j++) {
        await walkEntry(entries[j], '', pairs);
      }
    } else {
      var files = e.dataTransfer.files;
      for (var k = 0; k < files.length; k++) pairs.push([files[k], files[k].name]);
    }
    uploadPairs(pairs);
  });
})();
</script>
</body>
</html>"""
