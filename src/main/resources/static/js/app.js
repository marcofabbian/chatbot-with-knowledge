const form = document.getElementById('question-form');
const questionInput = document.getElementById('question');
const answers = document.getElementById('answers');
const errorMessage = document.getElementById('error-message');
const sendButton = document.getElementById('send-button');
let knowledgeAvailable = false;

async function checkKnowledgeStatus() {
    try {
        const response = await fetch('/api/knowledge/status', { cache: 'no-store' });
        if (!response.ok) throw new Error('Status unavailable');
        const status = await response.json();
        if (typeof status.available !== 'boolean') throw new Error('Invalid status');
        knowledgeAvailable = status.available;
        if (!knowledgeAvailable) {
            errorMessage.textContent = 'The knowledge base is unavailable. Questions are disabled until it is restored.';
        }
    } catch (error) {
        knowledgeAvailable = false;
        errorMessage.textContent = 'Unable to check the knowledge base. Questions are disabled.';
    }
    questionInput.disabled = !knowledgeAvailable;
    sendButton.disabled = !knowledgeAvailable;
}

checkKnowledgeStatus();

function addMessage(sender, text) {
    const qaPair = document.createElement('div');
    qaPair.className = 'qa-pair';

    const questionWrapper = document.createElement('div');
    questionWrapper.className = 'message message--question';
    questionWrapper.innerHTML = '<span class="message-label">You</span><p></p>';
    questionWrapper.querySelector('p').textContent = sender === 'You' ? text : '';

    const answerWrapper = document.createElement('div');
    answerWrapper.className = 'message message--answer';
    answerWrapper.innerHTML = '<span class="message-label">Chatbot</span><p></p>';
    answerWrapper.querySelector('p').textContent = sender === 'Chatbot' ? text : '';

    if (sender === 'You') {
        qaPair.appendChild(questionWrapper);
    }
    if (sender === 'Chatbot') {
        qaPair.appendChild(answerWrapper);
    }

    answers.appendChild(qaPair);
}

const CITATION = /\[[^\[\]\r\n]+ p\.\d+\]/g;
// Scrolls the answers list so the given question/answer pair starts at the top of the view.
function scrollToPair(qaPair) {
    const offset = qaPair.getBoundingClientRect().top - answers.getBoundingClientRect().top;
    answers.scrollTo({ top: answers.scrollTop + offset - 8, behavior: 'smooth' });
}
// Logs the question immediately and returns the pending answer paragraph holder.
function appendQuestion(question) {
    const qaPair = document.createElement('div');
    qaPair.className = 'qa-pair';
    const questionBlock = document.createElement('div');
    questionBlock.className = 'message message--question';
    questionBlock.innerHTML = '<span class="message-label">You</span>';
    const questionText = document.createElement('p');
    questionText.textContent = question;
    questionBlock.appendChild(questionText);
    const answerBlock = document.createElement('div');
    answerBlock.className = 'message message--answer';
    answerBlock.innerHTML = '<span class="message-label">Chatbot</span>';
    const answerText = document.createElement('p');
    answerText.textContent = 'Thinking...';
    answerBlock.appendChild(answerText);
    qaPair.appendChild(questionBlock);
    qaPair.appendChild(answerBlock);
    answers.appendChild(qaPair);
    scrollToPair(qaPair);
    return { qaPair, answerBlock, answerText };
}
// One line per sentence/line; each distinct source is listed only once at the end.
function fillAnswer(target, answer) {
    const sources = [];
    const lines = answer.split(/\r?\n/).map(line => {
        (line.match(CITATION) || []).forEach(c => { if (!sources.includes(c)) sources.push(c); });
        return line.replace(CITATION, '').replace(/\s+([.,;:])/g, '$1').trim();
    }).filter(Boolean);
    target.answerBlock.removeChild(target.answerText);
    lines.forEach(line => {
        const p = document.createElement('p');
        p.textContent = line;
        target.answerBlock.appendChild(p);
    });
    if (sources.length) {
        const p = document.createElement('p');
        p.className = 'answer-sources';
        p.textContent = 'Source: ' + sources.join(' ');
        target.answerBlock.appendChild(p);
    }
    scrollToPair(target.qaPair);
}
// Enter submits; Shift+Enter inserts a newline.
questionInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        form.requestSubmit();
    }
});
form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!knowledgeAvailable || sendButton.disabled) return;
    const question = questionInput.value.trim();
    if (!question) {
        errorMessage.textContent = 'Please enter a question before sending.';
        questionInput.focus();
        return;
    }
    errorMessage.textContent = '';
    sendButton.disabled = true;
    sendButton.textContent = 'Sending...';
    const pending = appendQuestion(question);
    form.reset();
    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question })
        });
        const data = await response.json();
        if (!response.ok) {
            if (response.status === 503) await checkKnowledgeStatus();
            throw new Error(data.error || 'Unable to get a response from the chatbot.');
        }
        fillAnswer(pending, data.answer);
    } catch (error) {
        pending.qaPair.remove();
        questionInput.value = question;
        errorMessage.textContent = error.message || 'Something went wrong while contacting the chatbot.';
    } finally {
        sendButton.disabled = !knowledgeAvailable;
        sendButton.textContent = 'Send';
        questionInput.focus();
    }
});
