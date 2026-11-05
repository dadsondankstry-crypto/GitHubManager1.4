# GitHub Manager 1.4 — Android / ZIP para gerar APK

Aplicativo Android Java para gerenciar repositórios pelo celular. Baseado na versão 1.3, preservando as funções de envio, edição e gerenciamento GitHub. Inclui downloads nativos (sem depender do botão de download da WebView).

## Instalar ou atualizar pelo GitHub (celular)

1. **Guarde seu token** e confirme qual é o repositório do **próprio GitHub Manager**. Não envie este projeto para `MinhaEscala`, `Guardiao de Dados`, etc.
2. No GitHub Manager atual, abra esse repositório e escolha **Enviar ZIP**; selecione este ZIP original sem extrair. Todos os arquivos estão diretamente na raiz, incluindo `.github/workflows/android.yml`.
3. Entre em **Actions** do repositório do GitHub Manager. O workflow **Gerar APK GitHub Manager** gera o artefato `GitHubManager-1_4-APK` após execução bem-sucedida.
4. Baixe o artefato, extraia `app-debug.apk` e instale.
5. A compilação é **debug**. Como o GitHub normalmente produz assinaturas debug diferentes entre ambientes, pode ocorrer conflito de assinatura; nesse caso é necessário remover a versão anterior antes de instalar. **Remover o app apaga o token salvo nele**, então mantenha uma cópia segura fora do app.

**Estado de teste:** YAML e XML conferidos. Código Java verificado com compilação de verificação contra *interfaces simuladas* do Android. **Não foi possível compilar com o SDK Android real neste ambiente**, nem testar no POCO X7 Pro; o GitHub Actions fará essa parte.

## Novidade 1: ZIP -> criar repositório -> publicar

**Início** → **🚀 ZIP → criar repositório e publicar**.

- Selecione o ZIP. O app verifica localmente a quantidade/tamanho dos arquivos.
- O **nome sugerido** vem do nome do ZIP, sem extensão, acentos ou caracteres especiais; espaços viram `-`. Exemplo: `Minha Escala versão 4.zip` → `Minha-Escala-versao-4`.
- Na janela você normalmente só precisa escolher **Público/Privado**. Há opção discreta de alterar o nome manualmente.
- O app consulta a disponibilidade do nome e propõe sufixo `-2`, `-3`, etc., se já houver um repositório com aquele nome **visível ao token**. Exibe o nome final e pede confirmação para evitar sobrescrita de repositórios.
- Cria o repositório e publica o conteúdo do ZIP na raiz, com diretórios e arquivos ocultos, preferencialmente em um único commit. Mostra progresso por arquivo.
- **O ZIP precisa conter o próprio workflow** (`.github/workflows/*.yml`) e os arquivos Android se quiser gerar APK. O aplicativo não escreve um workflow automaticamente em projetos que não o possuem.
- O novo repositório pode ficar criado mesmo se a publicação falhar; o app explica como retomar o envio ao repositório existente.

## Novidade 2: consultar o build e baixar APK sem navegador

Abra o repositório desejado no app → **Ações do repositório** ou **Actions**.

- **Verificar última compilação:** status, conclusão, número, branch e link para artefatos.
- **📲 Baixar último APK diretamente:** procura a execução bem-sucedida recente com um artefato cujo nome contém `APK` (não confunde com qualquer execução anterior). Selecione o artefato.
- Escolha **Salvar APK pronto (.apk)**: o aplicativo baixa o ZIP do artefato e extrai o primeiro `.apk` durante a gravação, sem precisar armazenar o ZIP inteiro primeiro.
- Ou escolha **Salvar artefato ZIP (.zip)**: grava o ZIP original do GitHub.
- O Android pergunta onde salvar. Depois de salvar o APK há opção **Abrir para instalar**. A instalação exige sua confirmação e, quando necessário, habilitar **Instalar apps desconhecidos** para este app.
- **Listar artefatos disponíveis** permite recuperar outros APKs/ZIPs enquanto não tiverem expirado.
- Na WebView de Actions incluí um botão **⬇ APK**: essa é a operação de download via API, independente da interface do site. Continua disponível abrir o Actions no navegador externo.
- **Permissão adicional no token para download de artefatos privados: Actions → Read.** Esta permissão não substitui Contents: Write no upload.

## Novidade 3: baixar repositórios, pastas e arquivos

Na tela de arquivos do repositório:

- Toque num arquivo → **⬇️ Baixar arquivo** (sem ZIP, como arquivo individual).
- Marque vários arquivos/pastas → **Baixar selecionados (ZIP)** ou **Baixar selecionados sem ZIP** (salva numa subpasta nova do local escolhido).
- **Baixar pasta atual (ZIP)**, incluindo conteúdo e subpastas.
- **Baixar repositório completo (ZIP)**, preservando os arquivos e as pastas como o GitHub entrega.
- **Baixar repositório completo (pasta)**: extrai o ZIP do GitHub numa subpasta nova do local escolhido pelo Android, sem precisar salvar um ZIP intermediário.
- Arquivos ocultos como `.github/workflows/android.yml` e `.gitignore` são preservados no download.
- Download de arquivo(s) selecionado(s) usa a API de blobs individualmente; limite de **400 arquivos**, **18 MB por arquivo**, **100 MB total**. Para repositórios maiores prefira o download de repositório completo (ZIP ou pasta).
- Download do repositório inteiro tem limite de segurança de **800 MB ZIP**, ou **1 GB extraído/12.000 arquivos** (pode consumir muita franquia de internet). O Android pode impedir selecionar certas pastas protegidas, incluindo a raiz de Downloads.

## Melhorias existentes preservadas

- Pesquisa/ordenação de repositórios, identificação do proprietário sem mostrar `null`.
- Criar e renomear repositório, alterar descrição, configurar GitHub Pages, excluir com confirmação.
- Enviar ZIP para repositório existente, arquivo avulso, múltiplos arquivos e pasta com subpastas.
- Criar pasta com `.gitkeep`, criar/editar textos, copiar/mover, exclusões múltiplas.
- Progresso de upload por arquivo, diagnóstico das permissões, abrir Actions na WebView e no navegador.
- ZIP de upload: no máximo **250 arquivos**, **18 MB no lote**, **5 MB por arquivo**.

## Permissões GitHub e segurança

- Para ver e baixar arquivos: **Contents: Read** no(s) repositório(s).
- Para enviar/alterar/excluir arquivos: **Contents: Read and write**; ao publicar workflows `.github/workflows/`, pode ser necessário **Workflows: Read and write**.
- Para criar repositórios: **Repository creation: Write** ou **Administration: Write**.
- Para consultar/baixar artefatos Actions: **Actions: Read** (relevante especialmente para repositórios privados).
- Se token fine-grained for `Only select repositories`, inclua o repositório no acesso. Repositórios criados depois podem exigir ajuste manual na seleção.
- Links de download do Actions expiram; o app os obtém na hora e **não envia o token para os servidores de arquivos de redirecionamento**.
- Arquivos ZIP e APK podem ser grandes. Confira o tamanho antes de baixar na internet limitada.
- Token salvo em preferências privadas Android sem criptografia adicional por Android Keystore (limitação herdada). Não compartilhe nem publique o token.

## Dicas para problemas

- **Não aparece artefato:** build pode estar em execução, com falha, sem `actions/upload-artifact` ou já expirado. Veja **Consultar última compilação**.
- **GitHub HTTP 403:** confira `Actions: Read` para baixar artefatos, `Contents: Read and write` para alterar arquivos e `Repository creation: Write` para criar repositório.
- **GitHub HTTP 404:** o repositório pode estar invisível ao token; confirme a conta proprietária e a seleção de repositórios. Falha intermitente de rede também pode ocorrer.
- **Instalação Android não abre:** abra o APK diretamente na pasta onde foi salvo. Pode ser necessário autorizar instalação de fontes externas.
- **Download interrompido:** arquivo de destino pode ficar incompleto; apague-o e repita. Não há suporte a retomar uma transferência de onde parou.
