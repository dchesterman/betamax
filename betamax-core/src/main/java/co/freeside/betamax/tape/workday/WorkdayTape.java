package co.freeside.betamax.tape.workday;

import org.yaml.snakeyaml.nodes.Tag;

import co.freeside.betamax.io.FileResolver;
import co.freeside.betamax.message.Request;
import co.freeside.betamax.message.Response;
import co.freeside.betamax.tape.MemoryTape;

public class WorkdayTape extends MemoryTape {
	
	public static final Tag TAPE_TAG = new Tag("!tape");
    public static final Tag FILE_TAG = new Tag("!file");
    private transient boolean dirty;
    
	protected WorkdayTape(FileResolver fileResolver) {
		super(fileResolver);
	}

	@Override
    public boolean isDirty() {
        return dirty;
    }
	
    @Override
	public boolean seek(Request request) {
		return dirty;
    	
    }
        
    @Override
    public Response play(final Request request) {
		return null;
    }
    
    @Override
    public void record(Request request, Response response) {
        dirty = true;
    }
}
