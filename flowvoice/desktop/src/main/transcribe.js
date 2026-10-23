'use strict';

const FILLER_HINT = {
  en: 'English',
  ru: 'Russian'
};

function joinUrl(base, tail) {
  return String(base || '').replace(/\/+$/, '') + tail;
}

/**
 * Send audio to an OpenAI-compatible /audio/transcriptions endpoint.
 * @param {Buffer} audio raw audio bytes
 * @param {string} mime e.g. 'audio/webm'
 * @param {object} settings
 * @returns {Promise<{text: string, language: string|null}>}
 */
async function transcribe(audio, mime, settings) {
  if (!settings.apiKey) {
    const err = new Error('NO_API_KEY');
    err.code = 'NO_API_KEY';
    throw err;
  }

  const ext = mime.includes('webm') ? 'webm' : mime.includes('ogg') ? 'ogg' : mime.includes('wav') ? 'wav' : 'mp4';
  const form = new FormData();
  form.append('file', new Blob([audio], { type: mime }), `speech.${ext}`);
  form.append('model', settings.sttModel || 'whisper-1');
  form.append('response_format', 'verbose_json');
  if (settings.language && settings.language !== 'auto') {
    form.append('language', settings.language);
  }
  const terms = (settings.dictionary || []).filter(Boolean).join(', ');
  if (terms) form.append('prompt', terms.slice(0, 900));

  const res = await fetch(joinUrl(settings.apiBaseUrl, '/audio/transcriptions'), {
    method: 'POST',
    headers: { Authorization: `Bearer ${settings.apiKey}` },
    body: form
  });

  const body = await res.text();
  if (!res.ok) {
    const err = new Error(`STT ${res.status}: ${body.slice(0, 300)}`);
    err.code = 'STT_HTTP';
    err.status = res.status;
    throw err;
  }

  let data;
  try {
    data = JSON.parse(body);
  } catch {
    return { text: body.trim(), language: null };
  }
  return { text: String(data.text || '').trim(), language: data.language || null };
}

/**
 * Optional LLM pass: punctuation, capitalisation, filler removal, dictionary fixes.
 * Falls back to the raw text on any failure — dictation must never be lost.
 */
async function cleanup(text, settings, detectedLanguage) {
  if (!settings.cleanup || !text) return text;

  const target = settings.language !== 'auto'
    ? settings.language
    : (detectedLanguage || '').slice(0, 2).toLowerCase();
  const langName = FILLER_HINT[target] || 'the same language as the input';
  const terms = (settings.dictionary || []).filter(Boolean);

  const system = [
    'You clean up raw speech-to-text output so it can be typed directly into a document.',
    `Reply in ${langName} — always the language the user actually spoke. Never translate.`,
    'Fix punctuation, capitalisation and obvious recognition slips.',
    'Remove filler words ("uh", "um", "эээ", "ну", "как бы") and false starts.',
    'Keep the wording, tone and meaning of the speaker. Do not summarise, answer, or add anything.',
    terms.length ? `Spell these terms exactly like this when they occur: ${terms.join(', ')}.` : '',
    'Output only the cleaned text, with no quotes and no commentary.'
  ].filter(Boolean).join('\n');

  try {
    const res = await fetch(joinUrl(settings.apiBaseUrl, '/chat/completions'), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${settings.apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: settings.cleanupModel || 'gpt-4o-mini',
        temperature: 0,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: text }
        ]
      })
    });
    if (!res.ok) return text;
    const data = await res.json();
    const out = data?.choices?.[0]?.message?.content;
    return out && out.trim() ? out.trim() : text;
  } catch {
    return text;
  }
}

module.exports = { transcribe, cleanup };
