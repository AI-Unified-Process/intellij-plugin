// Bundles the editor into one self-contained HTML page (scripts, styles and the BPMN
// font inlined) that the IDE loads into its embedded browser without a web server, and
// the renderer of BPMN fences into a script the Markdown preview loads.
import * as esbuild from 'esbuild';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const out = resolve(process.argv[2] ?? '../build/generated/bpmn-editor/bpmn-editor/editor.html');

const result = await esbuild.build({
  entryPoints: ['src/main.ts'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'chrome120',
  outdir: 'out',
  write: false,
  legalComments: 'none',
  // the embedded font already carries a WOFF data URL; the fallback formats are never loaded
  external: ['../font/*'],
});

const text = (extension) => result.outputFiles.find((file) => file.path.endsWith(extension)).text;
// neither may end its element early
const script = text('.js').replaceAll('</script', '<\\/script');
const style = text('.css').replaceAll('</style', '<\\/style');

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>BPMN Editor</title>
<style>${style}</style>
<script>/*AIUP_BRIDGE*/</script>
</head>
<body>
<div id="root">
<div id="error" hidden></div>
<div id="main"><div id="canvas"></div><div id="properties"></div></div>
</div>
<script>${script}</script>
</body>
</html>
`;

const preview = await esbuild.build({
  entryPoints: ['src/preview.ts'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'chrome120',
  outfile: 'out/preview.js',
  write: false,
  legalComments: 'none',
});
const previewOut = resolve(dirname(out), 'preview.js');

await mkdir(dirname(out), { recursive: true });
await writeFile(out, html);
await writeFile(previewOut, preview.outputFiles[0].contents);
console.log(`wrote ${out} (${Math.round(html.length / 1024)} KiB)`);
console.log(`wrote ${previewOut} (${Math.round(preview.outputFiles[0].contents.length / 1024)} KiB)`);
