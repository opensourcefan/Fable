package org.fable.service.metadata.parser;

import org.fable.service.network.OutboundHttpSupport;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class DefaultJsoupConnectionFactory implements JsoupConnectionFactory {
    @Override
    public Connection connect(String url) {
        return Jsoup.connect(url).timeout(OutboundHttpSupport.JSOUP_TIMEOUT_MS);
    }
}
