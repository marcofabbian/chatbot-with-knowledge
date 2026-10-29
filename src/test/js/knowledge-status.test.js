const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

async function setup(responses) {
    const calls = [];
    const elements = {};
    for (const id of ['question-form', 'question', 'answers', 'error-message', 'send-button']) {
        elements[id] = {
            disabled: id === 'question' || id === 'send-button',
            value: '',
            textContent: '',
            addEventListener(type, handler) { this.handler = handler; },
            focus() {},
            reset() {}
        };
    }
    const document = { getElementById: id => elements[id] };
    const fetch = async (url) => {
        calls.push(url);
        const next = responses.shift();
        if (next instanceof Error) throw next;
        if (!next) throw new Error('Unexpected request: ' + url);
        return { ok: next.status >= 200 && next.status < 300, status: next.status, json: async () => next.body };
    };
    vm.runInNewContext(script, { document, fetch, Error });
    await new Promise(resolve => setImmediate(resolve));
    return { elements, calls };
}

async function submit(elements) {
    elements.question.value = 'What are bonds?';
    await elements['question-form'].handler({ preventDefault() {} });
}

(async () => {
    let scenario = await setup([{ status: 200, body: { available: false } }]);
    assert(scenario.elements.question.disabled);
    assert(scenario.elements['send-button'].disabled);
    await submit(scenario.elements);
    assert.deepStrictEqual(scenario.calls, ['/api/knowledge/status']);

    scenario = await setup([new Error('network')]);
    assert(scenario.elements.question.disabled);
    assert(scenario.elements['error-message'].textContent.includes('Unable to check'));

    scenario = await setup([
        { status: 200, body: { available: true } },
        { status: 422, body: { error: 'Not found' } }
    ]);
    assert(!scenario.elements.question.disabled);
    await submit(scenario.elements);
    assert(!scenario.elements['send-button'].disabled);
    assert.strictEqual(scenario.elements['error-message'].textContent, 'Not found');

    scenario = await setup([
        { status: 200, body: { available: true } },
        { status: 503, body: { error: 'Knowledge unavailable' } },
        { status: 200, body: { available: false } }
    ]);
    await submit(scenario.elements);
    assert(scenario.elements.question.disabled);
    assert(scenario.elements['send-button'].disabled);
    assert.strictEqual(scenario.elements['error-message'].textContent, 'Knowledge unavailable');

    scenario = await setup([
        { status: 200, body: { available: true } },
        { status: 503, body: { error: 'Provider busy' } },
        { status: 200, body: { available: true } }
    ]);
    await submit(scenario.elements);
    assert(!scenario.elements['send-button'].disabled);
    assert.strictEqual(scenario.elements['error-message'].textContent, 'Provider busy');
    console.log('UI availability, network failure, 422 and 503 checks passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
