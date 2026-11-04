const { ExternalEditor } = require('external-editor');
const config = require('../.cz-config.js');

function message(answers) {
  const scope = String(answers.scope || '').trim();
  let result = `${answers.type}${scope ? `(${scope})` : ''}: ${answers.subject.trim()}`;
  for (const field of ['body', 'footer']) {
    if (!config.skipQuestions.includes(field) && answers[field]?.trim()) {
      result += `\n\n${field === 'footer' ? 'ISSUES CLOSED: ' : ''}${answers[field].trim().replaceAll('|', '\n')}`;
    }
  }
  return result;
}

function validateSubject(value) {
  if (!value.trim()) return '请填写提交说明';
  return value.length <= config.subjectLimit || `提交说明最多 ${config.subjectLimit} 字`;
}

module.exports = {
  async prompter(cz, commit) {
    const questions = [
      { type: 'list', name: 'type', message: config.messages.type, choices: config.types },
      { type: 'input', name: 'scope', message: config.messages.customScope },
      { type: 'input', name: 'subject', message: config.messages.subject, validate: validateSubject },
      { type: 'input', name: 'body', message: config.messages.body },
      { type: 'input', name: 'footer', message: config.messages.footer },
      {
        type: 'expand', name: 'confirmCommit', default: 0,
        choices: [
          { key: 'y', name: '确认提交', value: 'yes' },
          { key: 'n', name: '取消提交', value: 'no' },
          { key: 'e', name: '编辑提交说明', value: 'edit' }
        ],
        // Inquirer's expand prompt supplies the h/help choice.
        message(answers) { console.log(`\n${message(answers)}\n`); return config.messages.confirmCommit; }
      }
    ].filter(question => !config.skipQuestions.includes(question.name));
    const answers = await cz.prompt(questions);
    if (!['yes', 'edit'].includes(answers.confirmCommit)) return;
    if (!config.types.some(type => type.value === answers.type) || validateSubject(answers.subject) !== true) return;
    const draft = message(answers);
    if (answers.confirmCommit === 'yes') return commit(draft);

    let editor, edited;
    try {
      editor = new ExternalEditor(draft, { prefix: 'silver-care-commit-', postfix: '.txt', mode: 0o600 });
      edited = editor.run();
      if (editor.lastExitStatus !== 0 || !edited.trim()) throw new Error('编辑器未成功保存提交说明');
      editor.cleanup();
    } catch (error) {
      // Preserve the user's draft after a failed editor/cleanup; never commit on failure.
      console.error(`未提交：${error.message}\n${editor ? `草稿保留在：${editor.tempFile}` : draft}`);
      return;
    }
    commit(edited);
  }
};
