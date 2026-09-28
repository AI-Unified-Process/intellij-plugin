import Viewer from 'bpmn-js/lib/Viewer';

/*
 * The BPMN fences of the IDE's Markdown preview, as in the AI Unified Process Studio
 * (markdown-bpmn.ts): every ```bpmn code block is hidden and followed by its diagram,
 * laid out by a bpmn-js viewer in a hidden container — text measuring needs a rendered
 * DOM — and shown read-only as the exported SVG inside a shadow root. The preview
 * patches its DOM on every edit and drops nodes it did not create, so the renderings
 * are re-inserted after each patch, synchronously from a cache: only a changed fence is
 * laid out again, and nothing flickers.
 */

declare global {
  // eslint-disable-next-line no-var
  var IncrementalDOM: { notifications: { afterPatchListeners?: Array<() => void> } } | undefined;
}

const HOST_CLASS = 'aiup-bpmn';

/* The diagram stays black on white in the dark IDE theme. */
const HOST_STYLE = ':host { display: block; margin: 1em 0; padding: 8px; background: #fff; border-radius: 4px; }';

type Rendering = { svg: string } | { error: string };

/** Finished renderings by source, so a re-inserted fence renders synchronously. */
const renderings = new Map<string, Rendering>();
const CACHE_SIZE = 32;

/** The source each host element shows or is rendering. */
const hostSources = new WeakMap<Element, string>();

function renderFences(): void {
  const hosts = new Set<Element>();
  document.querySelectorAll('pre > code[class*="language-"]').forEach((code) => {
    if (!isBpmnFence(code)) return;
    const pre = code.parentElement!;
    pre.style.display = 'none';
    let host = pre.nextElementSibling;
    if (!host?.classList.contains(HOST_CLASS)) {
      host = document.createElement('div');
      host.className = HOST_CLASS;
      host.attachShadow({ mode: 'open' });
      pre.after(host);
    }
    hosts.add(host);
    renderFence(host, code.textContent ?? '');
  });
  // a deleted fence leaves its diagram behind when the preview keeps the node
  document.querySelectorAll(`div.${HOST_CLASS}`).forEach((host) => {
    if (!hosts.has(host)) host.remove();
  });
}

function isBpmnFence(code: Element): boolean {
  return Array.from(code.classList).some((name) => name.toLowerCase() === 'language-bpmn');
}

function renderFence(host: Element, source: string): void {
  if (hostSources.get(host) === source) return;
  hostSources.set(host, source);
  const cached = renderings.get(source);
  if (cached) {
    show(host, cached);
    return;
  }
  void renderSvg(source).then((rendering) => {
    if (hostSources.get(host) === source) show(host, rendering);
  });
}

function show(host: Element, rendering: Rendering): void {
  const root = host.shadowRoot!;
  if ('svg' in rendering) {
    root.innerHTML = `<style>${HOST_STYLE} svg { max-width: 100%; height: auto; }</style>${rendering.svg}`;
  } else {
    // an invalid fence shows the cause instead of the diagram
    root.innerHTML = `<style>${HOST_STYLE} pre { margin: 0; color: #b71c1c; white-space: pre-wrap; }</style>`;
    const message = document.createElement('pre');
    message.textContent = `BPMN: ${rendering.error}`;
    root.append(message);
  }
}

/** Fences render one after another, each in a fresh viewer that is dropped after. */
let queue: Promise<unknown> = Promise.resolve();

function renderSvg(source: string): Promise<Rendering> {
  const rendering = queue.then(async (): Promise<Rendering> => {
    const container = document.createElement('div');
    container.style.cssText = 'position:absolute;left:-10000px;top:0;width:1000px;height:1000px;visibility:hidden';
    document.body.append(container);
    const viewer = new Viewer({ container });
    try {
      await viewer.importXML(source);
      const { svg } = await viewer.saveSVG();
      return { svg };
    } catch (error: unknown) {
      return { error: error instanceof Error ? error.message : String(error) };
    } finally {
      viewer.destroy();
      container.remove();
    }
  });
  queue = rendering;
  return rendering.then((result) => {
    renderings.set(source, result);
    if (renderings.size > CACHE_SIZE) {
      renderings.delete(renderings.keys().next().value!);
    }
    return result;
  });
}

const notifications = globalThis.IncrementalDOM?.notifications;
if (notifications) {
  notifications.afterPatchListeners ??= [];
  notifications.afterPatchListeners.push(renderFences);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', renderFences);
} else {
  renderFences();
}
