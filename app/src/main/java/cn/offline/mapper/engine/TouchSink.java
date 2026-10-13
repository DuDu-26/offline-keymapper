package cn.offline.mapper.engine;

interface TouchSink {
    boolean contains(int id);
    void down(int id, float x, float y) throws Exception;
    void move(int id, float x, float y) throws Exception;
    void up(int id) throws Exception;
    void cancel() throws Exception;
}
