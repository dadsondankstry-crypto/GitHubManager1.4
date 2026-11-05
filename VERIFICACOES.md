# Verificações realizadas — GitHub Manager 1.4

- Preservado o código-base da versão 1.3 e adicionadas as funções de publicação automática, download via API GitHub e exportação de arquivos/pastas.
- Arquivo Java principal compilado com `javac` Java 17 usando **stubs de interface Android criados localmente**. Isso valida sintaxe, tipos Java e referências simuladas, mas **não é uma compilação Android real**.
- `AndroidManifest.xml` e recurso `ic_logo.xml` processados por analisador XML.
- YAML da pasta `.github/workflows/android.yml` analisado por PyYAML com gatilhos `push` na branch `main` e `workflow_dispatch`.
- Testada a integridade do ZIP de distribuição e seus caminhos ocultos.

## Testes ainda necessários no POCO X7 Pro / GitHub Actions

1. Enviar ZIP 1.4 ao repositório do GitHub Manager e confirmar compilação verde.
2. Escolher repositório com build verde, consultar status e baixar artefato ZIP e APK diretamente.
3. Abrir APK para instalar e verificar autorização Android para fontes desconhecidas.
4. Criar repositório público/privado a partir do nome normalizado do ZIP e publicar os arquivos.
5. Baixar arquivo individual, pasta selecionada ZIP e extraída, repositório completo ZIP e extraído.
6. Testar API com token fine-grained que possua `Actions: Read`, `Contents: Read and write` e `Repository creation: Write`.
7. Testar comportamento em conexão Wi-Fi lenta/interrompida; apagar arquivos de saída parciais se a transferência falhar.

Sem acesso a um SDK Android real nem credenciais do usuário neste ambiente, **não foi possível validar as chamadas GitHub reais nem a instalação do APK**.
