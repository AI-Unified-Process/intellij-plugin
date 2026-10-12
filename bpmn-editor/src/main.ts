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
 * An activity named after a use case ("UC-004 Find Owners") opens its spec: from the
 * context pad in the modeler, by a click in the viewer. The IDE passes the name pattern
 * to init, so it stays the one of UseCaseIndex.
 *
 * The IDE toolbar collapses the properties panel to give the diagram the whole width.
 *
 * Bridge: the IDE calls window.aiupBpmn.init/setXml/zoom/showProperties/exportSvg/exportPng; the page
 * answers through window.aiupBpmnSend (injected by the IDE) with "ready",
 * "changed:<xml>", "open-use-case:<id>", "svg:<svg>", "png:<base64>" and
 * "export-failed:<message>" messages.
 */

type Viewer = Modeler | NavigatedViewer;

type ZoomStep = 'in' | 'out' | 'fit';

declare global {
  interface Window {
    aiupBpmnSend?: (message: string) => void;
    aiupBpmn: {
      init(readOnly: boolean, useCaseRef: string, propertiesVisible: boolean): void;
      setXml(xml: string): void;
      zoom(step: ZoomStep): void;
      showProperties(visible: boolean): void;
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
// the leading use case ID of an activity name, group 1
let useCaseRef: RegExp | null = null;

const canvas = document.getElementById('canvas')!;
const panel = document.getElementById('properties')!;
const errorBar = document.getElementById('error')!;

function send(message: string): void {
  window.aiupBpmnSend?.(message);
}

function init(readOnly: boolean, useCaseRefSource: string, propertiesVisible: boolean): void {
  if (viewer) return;
  showProperties(propertiesVisible);
  useCaseRef = new RegExp(useCaseRefSource);
  if (readOnly) {
    panel.remove();
    viewer = new NavigatedViewer({ container: canvas });
    viewer.on('element.click', (event: { element: any }) => {
      const id = useCaseId(event.element);
      if (id) send('open-use-case:' + id);
    });
  } else {
    const modeler = new Modeler({
      container: canvas,
      propertiesPanel: { parent: panel },
      additionalModules: [BpmnPropertiesPanelModule, BpmnPropertiesProviderModule, UseCaseContextPadModule],
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

/** The use case an activity references by the leading ID of its name, if any. */
function useCaseId(element: any): string | null {
  const business = element?.businessObject;
  if (!useCaseRef || !business || element.labelTarget || !business.$instanceOf?.('bpmn:Activity')) return null;
  return useCaseRef.exec(business.name ?? '')?.[1] ?? null;
}

const OPEN_USE_CASE_ICON =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="none" stroke="#000" stroke-width="1.2">' +
      '<path d="M3.5 1.5h6l3 3v10h-9z"/><path d="M9.5 1.5v3h3M5.5 8h5M5.5 10.5h5M5.5 13h3"/></svg>',
  );

/** Adds "Open use case" to the context pad of an activity that references one. */
class UseCaseContextPad {
  static $inject = ['contextPad'];

  constructor(contextPad: any) {
    contextPad.registerProvider(this);
  }

  getContextPadEntries(element: any): Record<string, unknown> {
    const id = useCaseId(element);
    if (!id) return {};
    return {
      'aiup.open-use-case': {
        group: 'aiup',
        imageUrl: OPEN_USE_CASE_ICON,
        title: `Open use case ${id}`,
        action: { click: () => send('open-use-case:' + id) },
      },
    };
  }
}

const UseCaseContextPadModule = {
  __init__: ['aiupUseCaseContextPad'],
  aiupUseCaseContextPad: ['type', UseCaseContextPad],
};

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
    markUseCases();
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

/** Shows the activities that open a use case with a pointer in the viewer. */
function markUseCases(): void {
  if (!viewer || viewer instanceof Modeler) return;
  const drawing = viewer.get('canvas') as any;
  for (const element of (viewer.get('elementRegistry') as any).getAll()) {
    if (useCaseId(element)) drawing.addMarker(element, 'aiup-use-case');
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

/**
 * Zooms by the IDE toolbar: the wheel zoom of bpmn-js needs Ctrl/Cmd with the wheel or a
 * pinch, which the embedded browser does not always receive.
 */
function zoom(step: ZoomStep): void {
  if (!viewer || !imported) return;
  if (step === 'fit') {
    (viewer.get('canvas') as any).zoom('fit-viewport', 'auto');
  } else {
    (viewer.get('zoomScroll') as any).stepZoom(step === 'in' ? 1 : -1);
  }
}

/** Shows or collapses the properties panel; the canvas takes its width through the resize observer. */
function showProperties(visible: boolean): void {
  panel.hidden = !visible;
}

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

window.aiupBpmn = { init, setXml, zoom, showProperties, exportSvg: () => void exportSvg(), exportPng: () => void exportPng() };
send('ready');
