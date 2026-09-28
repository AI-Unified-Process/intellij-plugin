import Modeler from 'bpmn-js/lib/Modeler';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import { BpmnPropertiesPanelModule, BpmnPropertiesProviderModule } from 'bpmn-js-properties-panel';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css';
import './editor.css';

/*
 * The BPMN editor of the plugin, the same bpmn-js modeler the AI Unified Process Studio
 * uses: palette and canvas next to the properties panel of the selected element; for a
 * read-only file the navigable viewer without palette and panel. The IDE document holds
 * the BPMN 2.0 XML: a value set by the IDE is imported unchanged, and only an edit of
 * the user serializes the model again and reports it — importing fires no command, so
 * opening a file never marks it changed.
 *
 * Bridge: the IDE calls window.aiupBpmn.init/setXml/exportSvg/exportPng; the page
 * answers through window.aiupBpmnSend (injected by the IDE) with "ready",
 * "changed:<xml>", "svg:<svg>", "png:<base64>" and "export-failed:<message>" messages.
 */

type Viewer = Modeler | NavigatedViewer;

declare global {
  interface Window {
    aiupBpmnSend?: (message: string) => void;
    aiupBpmn: {
      init(readOnly: boolean): void;
      setXml(xml: string): void;
      exportSvg(): void;
      exportPng(): void;
    };
  }
}

let viewer: Viewer | null = null;
let currentXml = '';
let importSequence = 0;
let imported = false;
// while the XML cannot be displayed, an edit of the stale diagram must not overwrite it
let broken = false;
let debounce: number | undefined;
// the diagram is fitted once the canvas has a size: in a background editor tab it has none
let fitPending = false;

const canvas = document.getElementById('canvas')!;
const panel = document.getElementById('properties')!;
const errorBar = document.getElementById('error')!;

function send(message: string): void {
  window.aiupBpmnSend?.(message);
}

function init(readOnly: boolean): void {
  if (viewer) return;
  if (readOnly) {
    panel.remove();
    viewer = new NavigatedViewer({ container: canvas });
  } else {
    const modeler = new Modeler({
      container: canvas,
      propertiesPanel: { parent: panel },
      additionalModules: [BpmnPropertiesPanelModule, BpmnPropertiesProviderModule],
    } as any);
    modeler.on('commandStack.changed', (event: { trigger?: string }) => {
      // clearing the stack on an import is no edit of the user
      if (event.trigger !== 'clear' && !broken) {
        scheduleChange();
      }
    });
    viewer = modeler;
  }
  void importXml();
}

function setXml(xml: string): void {
  const value = xml ?? '';
  if (value === currentXml) return;
  currentXml = value;
  void importXml();
}

async function importXml(): Promise<void> {
  const current = viewer;
  if (!current) return;
  const sequence = ++importSequence;
  window.clearTimeout(debounce);
  const drawing = current.get('canvas') as any;
  // keep the zoom and scroll position when the text is edited next to the diagram
  const viewbox = imported ? drawing.viewbox() : null;
  try {
    let warnings = 0;
    if (currentXml.trim()) {
      const result = await current.importXML(currentXml);
      warnings = result.warnings.length;
    } else if (current instanceof Modeler) {
      // an empty file starts as a new diagram; it is written on the first edit
      await current.createDiagram();
    }
    if (sequence !== importSequence) return;
    if (viewbox && !fitPending) {
      drawing.viewbox(viewbox);
    } else {
      fitPending = true;
      fitToViewport();
    }
    imported = true;
    broken = false;
    showError(
      warnings > 0
        ? `${warnings} element(s) of this file are unknown to the editor and may not survive an edit in the diagram.`
        : null,
      'warning',
    );
  } catch (error: unknown) {
    if (sequence !== importSequence) return;
    broken = true;
    const message = error instanceof Error ? error.message : String(error);
    showError(`The BPMN file cannot be displayed: ${message} — fix the XML in the Text tab.`, 'error');
  }
}

/** Fits the diagram into the canvas, or on its next resize while it has no size (fitting then divides by zero). */
function fitToViewport(): void {
  if (!viewer || !fitPending || canvas.clientWidth === 0 || canvas.clientHeight === 0) return;
  fitPending = false;
  (viewer.get('canvas') as any).zoom('fit-viewport', 'auto');
}

new ResizeObserver(() => {
  if (!viewer) return;
  (viewer.get('canvas') as any).resized();
  fitToViewport();
}).observe(canvas);

function scheduleChange(): void {
  window.clearTimeout(debounce);
  debounce = window.setTimeout(async () => {
    if (!viewer) return;
    const { xml } = await viewer.saveXML({ format: true });
    if (xml !== undefined && xml !== currentXml) {
      currentXml = xml;
      send('changed:' + xml);
    }
  }, 150);
}

function showError(message: string | null, kind: 'error' | 'warning'): void {
  errorBar.textContent = message ?? '';
  errorBar.className = kind;
  errorBar.hidden = message === null;
}

/** Exports the shown diagram, unsaved edits included, as SVG. */
async function exportSvg(): Promise<void> {
  try {
    send('svg:' + (await saveSvg()));
  } catch (error: unknown) {
    send('export-failed:' + (error instanceof Error ? error.message : String(error)));
  }
}

/** Exports the shown diagram as PNG, at twice the size on white, as in the studio. */
async function exportPng(): Promise<void> {
  let url: string | null = null;
  try {
    url = URL.createObjectURL(new Blob([await saveSvg()], { type: 'image/svg+xml' }));
    const image = new Image();
    image.src = url;
    await image.decode();
    const scale = 2;
    const drawing = document.createElement('canvas');
    drawing.width = image.naturalWidth * scale;
    drawing.height = image.naturalHeight * scale;
    const context = drawing.getContext('2d')!;
    context.fillStyle = '#fff';
    context.fillRect(0, 0, drawing.width, drawing.height);
    context.drawImage(image, 0, 0, drawing.width, drawing.height);
    send('png:' + drawing.toDataURL('image/png').replace(/^data:image\/png;base64,/, ''));
  } catch (error: unknown) {
    send('export-failed:' + (error instanceof Error ? error.message : String(error)));
  } finally {
    if (url) URL.revokeObjectURL(url);
  }
}

async function saveSvg(): Promise<string> {
  if (!viewer) throw new Error('The diagram is not loaded yet.');
  const { svg } = await viewer.saveSVG();
  return svg;
}

window.aiupBpmn = { init, setXml, exportSvg: () => void exportSvg(), exportPng: () => void exportPng() };
send('ready');
