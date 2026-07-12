/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const MAX_BODY_BYTES = 2 * 1024 * 1024;
const MAX_VISIBLE_REQUESTS = 1000;
const CACHE_OVERRIDE_KEY = "firefoxCacheOverrideActive";
const REQUEST_FILTER = {urls: ["http://*/*", "https://*/*"]};

let recording = null;
const uiPorts = new Set();
const cacheRecovery = recoverStaleCacheOverride();

browser.action.onClicked.addListener(() => browser.sidebarAction.open());

browser.runtime.onConnect.addListener(port => {
  if (port.name !== "recorder-ui") {
    return;
  }
  uiPorts.add(port);
  port.onDisconnect.addListener(() => uiPorts.delete(port));
});

browser.runtime.onMessage.addListener(message => handleMessage(message));

browser.tabs.onRemoved.addListener(tabId => {
  if (!recording || recording.tabId !== tabId || recording.stopping) {
    return;
  }
  recording.attached = false;
  setRecordingBadge(false);
  notify("recorder-detached", {
    reason: "target_closed",
    message: "The recorded tab was closed. Captured requests can still be exported.",
    status: recorderStatus()
  });
});

browser.webRequest.onBeforeRequest.addListener(
  beforeRequest,
  REQUEST_FILTER,
  ["blocking", "requestBody"]
);
browser.webRequest.onBeforeSendHeaders.addListener(
  beforeSendHeaders,
  REQUEST_FILTER,
  ["requestHeaders"]
);
browser.webRequest.onHeadersReceived.addListener(
  headersReceived,
  REQUEST_FILTER,
  ["responseHeaders"]
);
browser.webRequest.onResponseStarted.addListener(responseStarted, REQUEST_FILTER);
browser.webRequest.onBeforeRedirect.addListener(beforeRedirect, REQUEST_FILTER);
browser.webRequest.onCompleted.addListener(completed, REQUEST_FILTER);
browser.webRequest.onErrorOccurred.addListener(failed, REQUEST_FILTER);

async function handleMessage(message) {
  switch (message?.type) {
    case "start-recording":
      return startRecording(message);
    case "new-transaction":
      return startTransaction(message.name);
    case "stop-recording":
      return stopRecording();
    case "recorder-status":
      return recorderStatus();
    default:
      return {ok: false, error: "Unknown recorder command"};
  }
}

async function startRecording(message) {
  await cacheRecovery;
  let {tabId} = message;
  const transactionName = String(message.transactionName || "").trim();
  if (!transactionName) {
    throw new Error("Enter a transaction name");
  }
  const startUrl = normalizeStartUrl(message.startUrl);
  const createBlankTab = message.createBlankTab === true;
  let tab;
  if (createBlankTab) {
    tab = await browser.tabs.create({
      windowId: Number.isInteger(message.windowId) ? message.windowId : undefined,
      url: "about:blank",
      active: true
    });
    tabId = tab.id;
  } else {
    if (!Number.isInteger(tabId)) {
      throw new Error("No active browser tab was selected");
    }
    tab = await browser.tabs.get(tabId);
  }
  if (!createBlankTab && !isRecordableUrl(tab.url)) {
    throw new Error(
      "This Firefox internal page cannot be recorded. Enter a Start URL or open an HTTP/HTTPS page."
    );
  }
  if (recording) {
    await discardCurrentRecording();
  }

  const disableCache = message.disableCache === true;
  const cacheOverrideApplied = await applyCacheOverride(disableCache);
  recording = {
    tabId,
    tabTitle: tab.title || "",
    incognito: Boolean(tab.incognito),
    disableCache,
    cacheOverrideApplied,
    startedAt: Date.now(),
    attached: true,
    stopping: false,
    transactions: [],
    currentTransaction: null,
    activeRequests: new Map(),
    pendingStates: new Set(),
    entries: [],
    summaries: [],
    capturedBodyBytes: 0,
    nextEntryOrdinal: 0
  };
  startTransactionInternal(transactionName);

  try {
    if (startUrl) {
      await browser.tabs.update(tabId, {url: startUrl, loadReplace: true});
    }
  } catch (error) {
    await discardCurrentRecording();
    throw error;
  }

  setRecordingBadge(true);
  notify("recording-started", recorderStatus());
  return recorderStatus();
}

function startTransaction(name) {
  if (!recording || recording.stopping) {
    throw new Error("No recording is active");
  }
  const transaction = startTransactionInternal(name);
  const status = recorderStatus();
  notify("transaction-started", {transaction, status});
  return {ok: true, transaction, status};
}

function startTransactionInternal(name) {
  const normalizedName = String(name || "").trim();
  if (!normalizedName) {
    throw new Error("Enter a transaction name");
  }
  const transaction = {
    id: `transaction-${recording.transactions.length + 1}`,
    name: normalizedName,
    startedDateTime: new Date().toISOString(),
    order: recording.transactions.length + 1,
    requestCount: 0
  };
  recording.transactions.push(transaction);
  recording.currentTransaction = transaction;
  return transaction;
}

async function stopRecording() {
  if (!recording) {
    throw new Error("No recording is active");
  }
  recording.stopping = true;
  const owner = recording;
  for (const state of [...owner.pendingStates]) {
    state.incomplete = !state.requestDone;
    completeBody(state);
    finalizeRequest(owner, state, state.finishedAt || Date.now());
    disconnectFilter(state);
  }
  owner.activeRequests.clear();
  owner.attached = false;
  setRecordingBadge(false);
  await releaseCacheOverride(owner);

  const har = buildHar(owner);
  const status = recorderStatus();
  recording = null;
  notify("recording-stopped", status);
  return {ok: true, har, status};
}

async function discardCurrentRecording() {
  if (!recording) {
    return;
  }
  const owner = recording;
  owner.stopping = true;
  for (const state of owner.pendingStates) {
    disconnectFilter(state);
  }
  await releaseCacheOverride(owner);
  setRecordingBadge(false);
  recording = null;
}

function beforeRequest(details) {
  const owner = recording;
  if (!captures(owner, details)) {
    return {};
  }
  const previous = owner.activeRequests.get(details.requestId);
  if (previous) {
    previous.incomplete = true;
    previous.requestDone = true;
    previous.finishedAt = details.timeStamp;
    completeBody(previous);
    finalizeRequest(owner, previous, details.timeStamp);
    owner.activeRequests.delete(details.requestId);
  }

  const transaction = owner.currentTransaction;
  const state = {
    owner,
    requestId: details.requestId,
    entryId: `${details.requestId}:${owner.nextEntryOrdinal + 1}`,
    ordinal: owner.nextEntryOrdinal++,
    tabId: details.tabId,
    startedAt: details.timeStamp,
    startedDateTime: new Date(details.timeStamp).toISOString(),
    resourceType: details.type || "other",
    transaction: {...transaction},
    request: {
      method: details.method || "GET",
      url: details.url,
      httpVersion: "",
      headers: [],
      queryString: queryToHar(details.url),
      cookies: [],
      headersSize: -1,
      bodySize: 0
    },
    response: emptyResponse(),
    bodyChunks: [],
    capturedBodyBytes: 0,
    receivedBodyBytes: 0,
    bodyDone: false,
    requestDone: false,
    bodyTruncated: false,
    bodyUnavailable: false,
    failed: false,
    incomplete: false
  };
  const postData = requestPostData(details.requestBody);
  if (postData) {
    state.request.postData = postData;
    state.request.bodySize = postData.text.length;
  } else if (details.requestBody?.error) {
    state.requestBodyUnavailable = true;
  }
  owner.activeRequests.set(details.requestId, state);
  owner.pendingStates.add(state);
  const activeTransaction = owner.transactions.find(item => item.id === transaction.id);
  if (activeTransaction) {
    activeTransaction.requestCount++;
  }
  attachResponseFilter(owner, state);
  return {};
}

function attachResponseFilter(owner, state) {
  try {
    const filter = browser.webRequest.filterResponseData(state.requestId);
    state.filter = filter;
    filter.ondata = event => {
      try {
        if (recording === owner && !state.finalized) {
          captureBodyChunk(state, event.data);
        }
      } finally {
        filter.write(event.data);
      }
    };
    filter.onstop = () => {
      filter.close();
      state.filter = null;
      state.bodyDone = true;
      completeBody(state);
      maybeFinalize(owner, state);
    };
    filter.onerror = () => {
      state.filter = null;
      state.bodyDone = true;
      if (responseMayHaveBody(state)) {
        state.bodyUnavailable = true;
        state.bodyUnavailableReason = filter.error || "Firefox response filter failed";
      }
      try {
        filter.disconnect();
      } catch (_ignored) {
        // Firefox may already have disconnected the failed filter.
      }
      maybeFinalize(owner, state);
    };
  } catch (error) {
    state.bodyDone = true;
    state.bodyUnavailable = true;
    state.bodyUnavailableReason = errorMessage(error);
  }
}

function captureBodyChunk(state, buffer) {
  const bytes = new Uint8Array(buffer);
  state.receivedBodyBytes += bytes.byteLength;
  const available = MAX_BODY_BYTES - state.capturedBodyBytes;
  if (available <= 0) {
    state.bodyTruncated = true;
    return;
  }
  const captured = bytes.slice(0, Math.min(available, bytes.byteLength));
  state.bodyChunks.push(captured);
  state.capturedBodyBytes += captured.byteLength;
  state.owner.capturedBodyBytes += captured.byteLength;
  if (captured.byteLength < bytes.byteLength) {
    state.bodyTruncated = true;
  }
}

function beforeSendHeaders(details) {
  const state = requestState(details);
  if (!state) {
    return;
  }
  state.request.headers = headersToHar(details.requestHeaders);
  state.sentAt = details.timeStamp;
  if (state.request.postData && !state.request.postData.mimeType) {
    state.request.postData.mimeType = headerValue(state.request.headers, "content-type");
  }
}

function headersReceived(details) {
  const state = requestState(details);
  if (!state) {
    return;
  }
  applyResponse(state, details);
  state.responseStartedAt = details.timeStamp;
}

function responseStarted(details) {
  const state = requestState(details);
  if (!state) {
    return;
  }
  applyResponse(state, details);
  state.responseStartedAt = details.timeStamp;
  state.serverIPAddress = details.ip || "";
  state.fromCache = Boolean(details.fromCache);
}

function beforeRedirect(details) {
  const owner = recording;
  const state = requestState(details);
  if (!state || !owner) {
    return;
  }
  applyResponse(state, details);
  state.response.redirectURL = details.redirectUrl || state.response.redirectURL;
  state.redirect = true;
  state.requestDone = true;
  state.finishedAt = details.timeStamp;
  owner.activeRequests.delete(details.requestId);
  maybeFinalize(owner, state);
}

function completed(details) {
  const owner = recording;
  const state = requestState(details);
  if (!state || !owner) {
    return;
  }
  applyResponse(state, details);
  state.fromCache = Boolean(details.fromCache);
  state.requestDone = true;
  state.finishedAt = details.timeStamp;
  owner.activeRequests.delete(details.requestId);
  maybeFinalize(owner, state);
}

function failed(details) {
  const owner = recording;
  const state = requestState(details);
  if (!state || !owner) {
    return;
  }
  state.failed = true;
  state.failureText = details.error || "Request failed";
  state.requestDone = true;
  state.finishedAt = details.timeStamp;
  state.bodyDone = true;
  disconnectFilter(state);
  owner.activeRequests.delete(details.requestId);
  completeBody(state);
  finalizeRequest(owner, state, details.timeStamp);
}

function requestState(details) {
  if (!captures(recording, details)) {
    return null;
  }
  return recording.activeRequests.get(details.requestId) || null;
}

function captures(owner, details) {
  return Boolean(owner
    && !owner.stopping
    && owner.attached
    && details.tabId === owner.tabId
    && /^https?:/i.test(details.url));
}

function maybeFinalize(owner, state) {
  if (recording !== owner || state.finalized || !state.requestDone || !state.bodyDone) {
    return;
  }
  completeBody(state);
  finalizeRequest(owner, state, state.finishedAt || Date.now());
}

function completeBody(state) {
  if (state.bodyStored) {
    return;
  }
  state.bodyStored = true;
  state.response.bodySize = state.receivedBodyBytes;
  state.response.content.size = state.receivedBodyBytes;
  if (state.capturedBodyBytes === 0) {
    return;
  }
  const bytes = concatenateBytes(state.bodyChunks, state.capturedBodyBytes);
  const mimeType = state.response.content.mimeType || "";
  if (isTextMimeType(mimeType)) {
    state.response.content.text = decodeText(bytes, mimeType);
  } else {
    state.response.content.text = bytesToBase64(bytes);
    state.response.content.encoding = "base64";
  }
  state.bodyChunks = [];
}

function finalizeRequest(owner, state, finishedAt) {
  if (state.finalized || recording !== owner) {
    return;
  }
  state.finalized = true;
  owner.pendingStates.delete(state);
  const end = Number.isFinite(finishedAt) ? finishedAt : state.startedAt;
  const totalMs = Math.max(end - state.startedAt, 0);
  const entry = {
    startedDateTime: state.startedDateTime,
    time: totalMs,
    request: state.request,
    response: state.response,
    cache: {},
    timings: buildTimings(state, totalMs),
    serverIPAddress: state.serverIPAddress || "",
    connection: "",
    _resourceType: state.resourceType,
    _breaktest: {
      transactionId: state.transaction.id,
      transactionName: state.transaction.name,
      transactionOrder: state.transaction.order,
      entryOrdinal: state.ordinal,
      bodyTruncated: state.bodyTruncated,
      bodyUnavailable: Boolean(state.bodyUnavailable),
      bodyUnavailableReason: state.bodyUnavailableReason || "",
      requestBodyUnavailable: Boolean(state.requestBodyUnavailable),
      failed: state.failed,
      incomplete: state.incomplete,
      failureText: state.failureText || ""
    }
  };
  if (state.fromCache) {
    entry._fromCache = "firefox-cache";
  }
  owner.entries.push(entry);

  const summary = {
    id: state.entryId,
    transactionId: state.transaction.id,
    transactionName: state.transaction.name,
    method: state.request.method,
    url: state.request.url,
    status: state.response.status,
    resourceType: state.resourceType,
    size: state.response.bodySize,
    failed: state.failed,
    bodyTruncated: state.bodyTruncated,
    bodyUnavailable: Boolean(state.bodyUnavailable)
  };
  owner.summaries.push(summary);
  if (owner.summaries.length > MAX_VISIBLE_REQUESTS) {
    owner.summaries.shift();
  }
  notify("request-finished", {summary, status: recorderStatus()});
}

function applyResponse(state, details) {
  const headers = details.responseHeaders
    ? headersToHar(details.responseHeaders)
    : state.response.headers;
  const statusLine = details.statusLine || state.response.statusText;
  const status = Number.isInteger(details.statusCode) ? details.statusCode : state.response.status;
  state.response.status = status || 0;
  state.response.statusText = statusText(statusLine);
  state.response.httpVersion = httpVersion(statusLine) || state.response.httpVersion;
  state.response.headers = headers;
  state.response.content.mimeType = mimeType(headers);
  state.response.redirectURL = headerValue(headers, "location") || state.response.redirectURL;
}

function buildTimings(state, totalMs) {
  const send = state.sentAt == null ? 0 : Math.max(state.sentAt - state.startedAt, 0);
  const wait = state.responseStartedAt == null || state.sentAt == null
    ? Math.max(totalMs - send, 0)
    : Math.max(state.responseStartedAt - state.sentAt, 0);
  const receive = state.responseStartedAt == null
    ? 0
    : Math.max((state.finishedAt || state.responseStartedAt) - state.responseStartedAt, 0);
  return {blocked: 0, dns: -1, connect: -1, ssl: -1, send, wait, receive};
}

function buildHar(owner) {
  const entries = [...owner.entries].sort((left, right) => {
    const timeDifference = Date.parse(left.startedDateTime) - Date.parse(right.startedDateTime);
    return timeDifference || left._breaktest.entryOrdinal - right._breaktest.entryOrdinal;
  });
  return {
    log: {
      version: "1.2",
      creator: {
        name: "BreakTest Browser Recorder",
        version: browser.runtime.getManifest().version
      },
      pages: [],
      entries,
      _breaktest: {
        formatVersion: 1,
        recordedWith: "firefox.webRequest",
        startedDateTime: new Date(owner.startedAt).toISOString(),
        tabTitle: owner.tabTitle,
        cacheDisabled: owner.disableCache,
        transactions: owner.transactions
      }
    }
  };
}

function recorderStatus() {
  if (!recording) {
    return {ok: true, active: false, summaries: []};
  }
  return {
    ok: true,
    active: !recording.stopping,
    attached: recording.attached,
    tabId: recording.tabId,
    currentTransaction: recording.currentTransaction,
    transactions: recording.transactions,
    requestCount: recording.entries.length,
    pendingCount: recording.pendingStates.size,
    capturedBodyBytes: recording.capturedBodyBytes,
    incognito: recording.incognito,
    cacheDisabled: recording.disableCache,
    summaries: recording.summaries
  };
}

async function applyCacheOverride(disableCache) {
  if (!disableCache) {
    return false;
  }
  const current = await browser.browserSettings.cacheEnabled.get({});
  if (current.value === false) {
    return false;
  }
  if (current.levelOfControl !== "controllable_by_this_extension"
      && current.levelOfControl !== "controlled_by_this_extension") {
    throw new Error("Firefox does not allow this extension to control the browser cache");
  }
  const changed = await browser.browserSettings.cacheEnabled.set({value: false});
  if (!changed) {
    throw new Error("Unable to disable the Firefox browser cache");
  }
  await browser.storage.local.set({[CACHE_OVERRIDE_KEY]: true});
  return true;
}

async function releaseCacheOverride(owner) {
  if (!owner.cacheOverrideApplied) {
    return;
  }
  owner.cacheOverrideApplied = false;
  await browser.browserSettings.cacheEnabled.clear({}).catch(() => {});
  await browser.storage.local.remove(CACHE_OVERRIDE_KEY).catch(() => {});
}

async function recoverStaleCacheOverride() {
  try {
    const stored = await browser.storage.local.get(CACHE_OVERRIDE_KEY);
    if (stored[CACHE_OVERRIDE_KEY]) {
      await browser.browserSettings.cacheEnabled.clear({});
      await browser.storage.local.remove(CACHE_OVERRIDE_KEY);
    }
  } catch (_ignored) {
    // A later recording attempt will report cache-control failures explicitly.
  }
}

function requestPostData(requestBody) {
  if (!requestBody) {
    return null;
  }
  if (requestBody.formData) {
    const params = [];
    const search = new URLSearchParams();
    for (const [name, values] of Object.entries(requestBody.formData)) {
      for (const value of values) {
        params.push({name, value});
        search.append(name, value);
      }
    }
    return {mimeType: "application/x-www-form-urlencoded", text: search.toString(), params};
  }
  if (!Array.isArray(requestBody.raw)) {
    return null;
  }
  const chunks = [];
  let size = 0;
  for (const part of requestBody.raw) {
    if (part.bytes) {
      const bytes = new Uint8Array(part.bytes);
      chunks.push(bytes);
      size += bytes.byteLength;
    }
  }
  if (size === 0) {
    return null;
  }
  return {mimeType: "", text: new TextDecoder().decode(concatenateBytes(chunks, size)), params: []};
}

function headersToHar(headers) {
  if (!Array.isArray(headers)) {
    return [];
  }
  return headers.map(header => ({
    name: header.name,
    value: header.value ?? binaryHeaderValue(header.binaryValue)
  }));
}

function binaryHeaderValue(value) {
  return Array.isArray(value) ? String.fromCharCode(...value) : "";
}

function headerValue(headers, wantedName) {
  const header = headers.find(item => item.name.toLowerCase() === wantedName.toLowerCase());
  return header ? header.value : "";
}

function mimeType(headers) {
  return headerValue(headers, "content-type").split(";", 1)[0].trim();
}

function queryToHar(url) {
  try {
    return [...new URL(url).searchParams.entries()].map(([name, value]) => ({name, value}));
  } catch (_ignored) {
    return [];
  }
}

function emptyResponse() {
  return {
    status: 0,
    statusText: "",
    httpVersion: "",
    headers: [],
    cookies: [],
    content: {size: 0, mimeType: ""},
    redirectURL: "",
    headersSize: -1,
    bodySize: -1,
    _transferSize: 0
  };
}

function concatenateBytes(chunks, size) {
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

function bytesToBase64(bytes) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  }
  return btoa(binary);
}

function decodeText(bytes, mime) {
  const match = /charset\s*=\s*([^;]+)/i.exec(mime);
  const charset = match ? match[1].trim().replace(/^['"]|['"]$/g, "") : "utf-8";
  try {
    return new TextDecoder(charset).decode(bytes);
  } catch (_ignored) {
    return new TextDecoder().decode(bytes);
  }
}

function isTextMimeType(mime) {
  return /^text\//i.test(mime)
    || /(json|xml|javascript|ecmascript|graphql|x-www-form-urlencoded|svg)/i.test(mime);
}

function responseMayHaveBody(state) {
  return state.request.method !== "HEAD"
    && state.response.status !== 204
    && state.response.status !== 304;
}

function httpVersion(statusLine) {
  const match = /^(HTTP\/[^\s]+)/i.exec(statusLine || "");
  return match ? match[1] : "";
}

function statusText(statusLine) {
  const match = /^HTTP\/[^\s]+\s+\d+\s*(.*)$/i.exec(statusLine || "");
  return match ? match[1] : "";
}

function disconnectFilter(state) {
  if (!state.filter) {
    return;
  }
  try {
    state.filter.disconnect();
  } catch (_ignored) {
    // The stream might already be complete.
  }
  state.filter = null;
  state.bodyDone = true;
}

function normalizeStartUrl(value) {
  const text = String(value || "").trim();
  if (!text) {
    return "";
  }
  let candidate = text;
  if (!/^[a-z][a-z0-9+.-]*:/i.test(candidate)) {
    candidate = /^(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(candidate)
      ? `http://${candidate}`
      : `https://${candidate}`;
  }
  let parsed;
  try {
    parsed = new URL(candidate);
  } catch (_ignored) {
    throw new Error("Enter a valid HTTP or HTTPS start URL");
  }
  if (!/^https?:$/.test(parsed.protocol)) {
    throw new Error("The Start URL must use HTTP or HTTPS");
  }
  return parsed.href;
}

function isRecordableUrl(url) {
  return typeof url === "string" && (/^https?:/i.test(url) || url === "about:blank");
}

function setRecordingBadge(active) {
  browser.action.setBadgeText({text: active ? "REC" : ""});
  if (active) {
    browser.action.setBadgeBackgroundColor({color: "#c62828"});
  }
}

function notify(type, payload = {}) {
  browser.runtime.sendMessage({type, ...payload}).catch(() => {
    // The sidebar may be closed; recording continues in the background page.
  });
}

function errorMessage(error) {
  return error?.message || String(error || "Unexpected recorder error");
}
