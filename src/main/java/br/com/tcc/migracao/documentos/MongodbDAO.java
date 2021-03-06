package br.com.tcc.migracao.documentos;

import br.com.tcc.auxiliares.MapaTabelas;
import br.com.tcc.auxiliares.No;
import br.com.tcc.bancoRelacional.Banco;
import br.com.tcc.bancoRelacional.Coluna;
import br.com.tcc.bancoRelacional.Tabela;
import br.com.tcc.conexao.relacional.Conexao;
import br.com.tcc.interfaceGrafica.TelaMigracao;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import javax.swing.JOptionPane;

/**
 *
 * @author claysllanxavier
 */
public class MongodbDAO {

    private final MongoClient mongoClient;
    private DB db;
    private DBCollection coll;
    private final TelaMigracao tela;

    public MongodbDAO(MongoClient mongo, TelaMigracao t) {
        this.mongoClient = mongo;
        this.tela = t;
    }

    public void criarBanco(String nome) {
        this.db = mongoClient.getDB(nome);
    }

    public void criarColecao(String nome) {
        this.coll = db.createCollection(nome, new BasicDBObject());
    }

    public DB getDb() {
        return db;
    }

    public DBCollection getColl(String colecao) {
        coll = db.getCollection(colecao);
        return coll;
    }

    public void save(Tabela tabela, ResultSet tupla, No pai) throws SQLException {
        Map<String, Object> mapTabela = new MapaTabelas().converterToMap(tabela, tupla, pai);
        getColl(tabela.getNome()).insert(new BasicDBObject(mapTabela));
    }

    public void trataRelacionamentos(Banco banco, No pai, String nomeBanco) throws SQLException {
        criarBanco(nomeBanco);
        for (No filho : pai.getFilho()) {
            trataRelacionamentos(banco, filho, nomeBanco);
            for (Tabela tabela : banco.getTabelas()) {
                if (tabela.getNome().equalsIgnoreCase(filho.getNomeTabela())) {
                    for (Coluna coluna : tabela.getColunas()) {
                        if (coluna.isChaveEstrangeira()) {
                            int subPartes = 10;
                            int inicio = 0;

                            int total = 20198310;
                            int sub_total = total / subPartes;
                            for (int i = 0; i < subPartes; i++) {
                                DBCursor cursor = getColl(filho.getNomeTabela()).find().skip(inicio).limit(sub_total);
                                while (cursor.hasNext()) {
                                    DBObject obj = cursor.next();
                                    BasicDBObject searchQuery = new BasicDBObject().append("_id", obj.get("_id"));
                                    BasicDBObject whereQuery = new BasicDBObject();
                                    if (obj.get(coluna.getNome()) != null) {
                                        whereQuery.put(coluna.getColunaForeignKeyReferencia(), obj.get(coluna.getNome()));
                                        DBCursor cursorAux = getColl(coluna.getTabelaForeignKeyReferencia()).find(whereQuery);
                                        ArrayList<Object> lista = new BasicDBList();
                                        while (cursorAux.hasNext()) {
                                            lista.add(cursorAux.next());
                                        }
                                        if (!lista.isEmpty()) {
                                            if (lista.size() == 1) {
                                                BasicDBObject doc1 = new BasicDBObject();
                                                doc1.put(coluna.getTabelaForeignKeyReferencia(), lista.get(0));
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$set", doc1));
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$unset", new BasicDBObject(coluna.getNome(), "")));
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$unset", new BasicDBObject(coluna.getTabelaForeignKeyReferencia() + "._id", "")));
                                            } else {
                                                BasicDBObject doc1 = new BasicDBObject();
                                                doc1.put(coluna.getTabelaForeignKeyReferencia(), lista);
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$set", doc1));
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$unset", new BasicDBObject(coluna.getNome(), "")));
                                                getColl(filho.getNomeTabela()).update(searchQuery, new BasicDBObject("$unset", new BasicDBObject(coluna.getTabelaForeignKeyReferencia() + "._id", "")));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void migrarDados(Conexao c, Banco banco, String nomeBanco, No arvore) throws SQLException {
        tela.atualizaAreaInformacoes("Criou o banco de dados: " + nomeBanco);
        criarBanco(nomeBanco);
        try {
            for (Tabela tabela : banco.getTabelas()) {
                tela.atualizaAreaInformacoes("Criou a coleção: " + tabela.getNome());
                criarColecao(tabela.getNome());
                int subPartes = 10;
                long inicio = 0;

                long total = 20198310;
                long sub_total = total / subPartes;
                tela.atualizaAreaInformacoes("Transferindo dados...");
                for (int i = 0; i < subPartes; i++) {
                    String sql = "SELECT * FROM " + tabela.getNome() + " LIMIT " + inicio + "," + sub_total;
                    try (PreparedStatement stmt = c.getC().prepareStatement(sql)) {
                        ResultSet resultado = stmt.executeQuery();
                        while (resultado.next()) {
                            save(tabela, resultado, arvore);
                        }
                    }
                    inicio = inicio + sub_total;
                }
                tela.atualizaAreaInformacoes("Todos os dados da tabela: " + tabela.getNome() + " foram transferidos...");
                tela.atualizaAreaInformacoes("Iniciando validação de toltal de registros...");
                if (validarTotalRegistros(c, tabela, nomeBanco)) {
                    tela.atualizaAreaInformacoes("Os dados foram migrados corretamente...");
                } else {
                    JOptionPane.showMessageDialog(null, "Ocorreu um erro na migração. Por favor refaça o processo!", "ERRO", JOptionPane.ERROR_MESSAGE);
                    tela.dispose();
                }
            }
        } catch (com.mongodb.MongoCommandException e) {
            JOptionPane.showMessageDialog(null, "Ja existe esse banco de dados no MongoDB!", "ERRO", JOptionPane.ERROR_MESSAGE);
            tela.dispose();
        }
        tela.atualizaAreaInformacoes("Todos os dados foram migrados corretamente!");
        tela.atualizaAreaInformacoes("Iniciando o processo de tratamento dos relacionamentos!");
    }

    public boolean validarTotalRegistros(Conexao c, Tabela tabela, String nomeBanco) throws SQLException {
        criarBanco(nomeBanco);
        long resultSQL = 0;
        long resultMongo = 0;
        String sql = "SELECT count(*) FROM " + tabela.getNome();
        try (PreparedStatement stmt = c.getC().prepareStatement(sql)) {
            ResultSet resultado = stmt.executeQuery();
            while (resultado.next()) {
                resultSQL = resultado.getLong(1);
            }
            resultMongo = getColl(tabela.getNome()).count();
            if (resultSQL == resultMongo) {
                return true;
            }
        }
        return false;
    }
}
