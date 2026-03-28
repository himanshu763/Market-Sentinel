import { useState } from 'react';
import { useProductComparison } from './hooks/useProductComparison';

function App() {
  const [inputUrl, setInputUrl] = useState('');
  const { data, loading, error, fetchComparison } = useProductComparison();

  const handleSearch = (e) => {
    e.preventDefault();
    if (inputUrl) fetchComparison(inputUrl);
  };

  const getConfidenceBadge = (score) => {
    const percentage = Math.round(score * 100);
    if (score >= 0.7) return <span className="bg-emerald-100 text-emerald-700 text-xs px-2.5 py-1 rounded-full font-bold border border-emerald-200">{percentage}% Match</span>;
    if (score >= 0.5) return <span className="bg-amber-100 text-amber-700 text-xs px-2.5 py-1 rounded-full font-bold border border-amber-200">{percentage}% Match</span>;
    return <span className="bg-rose-100 text-rose-700 text-xs px-2.5 py-1 rounded-full font-bold border border-rose-200">{percentage}% Match</span>;
  };

  return (
    <div className="min-h-screen pb-20">
      {/* 1. GLASS NAV */}
      <header className="bg-white/80 backdrop-blur-md border-b border-slate-200 sticky top-0 z-50 transition-all">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex justify-between items-center">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-600 rounded-xl shadow-lg shadow-indigo-200 flex items-center justify-center transform -rotate-6">
              <span className="text-white font-black text-2xl leading-none">O</span>
            </div>
            <h1 className="text-2xl font-black tracking-tight text-slate-900">
              Omni<span className="text-indigo-600">Kart</span>
            </h1>
          </div>
          <div className="hidden sm:block">
            <span className="px-3 py-1 bg-slate-100 text-slate-500 rounded-full text-xs font-bold tracking-widest uppercase">
              v2.0 Beta
            </span>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        
        {/* 2. HERO SEARCH SECTION */}
        <section className="pt-16 pb-20 text-center">
          <h2 className="text-4xl md:text-5xl font-extrabold text-slate-900 mb-6 tracking-tight">
            Stop overpaying. <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-600 to-violet-600">Scan the web.</span>
          </h2>
          <p className="text-slate-500 text-lg mb-10 max-w-2xl mx-auto">
            Paste any product link below and we'll instantly find better deals across major marketplaces.
          </p>
          
          <form onSubmit={handleSearch} className="max-w-3xl mx-auto group">
            <div className="relative flex items-center p-2 bg-white rounded-2xl shadow-2xl border border-slate-200 focus-within:ring-4 focus-within:ring-indigo-100 transition-all">
              <input 
                type="text" 
                placeholder="Paste Amazon or Flipkart URL..."
                className="flex-1 py-4 px-6 outline-none text-slate-700 text-lg bg-transparent"
                value={inputUrl}
                onChange={(e) => setInputUrl(e.target.value)}
              />
              <button 
                type="submit" 
                className="bg-indigo-600 text-white px-10 py-4 rounded-xl font-bold text-lg hover:bg-indigo-700 hover:shadow-lg active:scale-95 transition-all disabled:opacity-50"
                disabled={loading}
              >
                {loading ? 'Scanning...' : 'Compare'}
              </button>
            </div>
          </form>
        </section>

        {/* 3. RESULTS AREA */}
        {loading && (
          <div className="flex flex-col items-center justify-center py-20 animate-pulse">
            <div className="relative w-20 h-20 mb-6">
              <div className="absolute inset-0 border-4 border-indigo-100 rounded-full"></div>
              <div className="absolute inset-0 border-4 border-indigo-600 rounded-full border-t-transparent animate-spin"></div>
            </div>
            <p className="text-indigo-600 font-bold text-lg">Analyzing market data...</p>
          </div>
        )}

        {data && (
          <div className="grid grid-cols-1 lg:grid-cols-4 gap-10">
            <div className="lg:col-span-3 space-y-10">
              
              {/* PRIMARY PRODUCT */}
              {data.results && data.results[0]?.product && (
                <div className="bg-white rounded-3xl shadow-xl shadow-slate-200/50 border border-slate-100 overflow-hidden flex flex-col md:flex-row group transition-all hover:border-indigo-200">
                  <div className="p-8 flex-1">
                    <div className="flex items-center gap-2 mb-4">
                        <span className="w-2 h-2 rounded-full bg-indigo-500 animate-ping"></span>
                        <span className="text-indigo-600 text-[10px] font-black uppercase tracking-widest">Tracking Live Price</span>
                    </div>
                    <h2 className="text-2xl md:text-3xl font-bold text-slate-900 mb-4 group-hover:text-indigo-600 transition-colors">
                      {data.results[0].product.title}
                    </h2>
                    <div className="flex items-center gap-4 text-slate-400">
                        <div className="flex items-center gap-1.5 bg-slate-50 px-3 py-1 rounded-md">
                            <span className="w-4 h-4 rounded-full bg-slate-200"></span>
                            <span className="text-xs font-bold capitalize">{data.results[0].platform}</span>
                        </div>
                    </div>
                  </div>
                  <div className="bg-slate-50 p-8 flex flex-col justify-center items-center md:items-end border-t md:border-t-0 md:border-l border-slate-100 min-w-[280px]">
                    <span className="text-slate-400 text-sm font-bold uppercase mb-1">Found For</span>
                    <div className="text-4xl font-black text-slate-900 mb-6 tracking-tight">
                        {data.results[0].product.price}
                    </div>
                    <a href={data.results[0].product.productUrl} className="w-full text-center bg-slate-900 text-white font-bold py-4 rounded-xl hover:bg-black transition-all shadow-lg">
                      Visit Store
                    </a>
                  </div>
                </div>
              )}

              {/* ALTERNATIVES */}
              {data.similarProducts?.length > 0 && (
                <div>
                  <h3 className="text-xl font-black text-slate-900 mb-8 flex items-center gap-3">
                    <span className="w-8 h-[2px] bg-indigo-600"></span>
                    Market Alternatives
                  </h3>
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
                    {data.similarProducts.map((item, idx) => (
                      <div key={idx} className="bg-white rounded-2xl p-6 border border-slate-200 hover:border-indigo-400 hover:shadow-2xl hover:shadow-indigo-100 transition-all group flex flex-col relative">
                        {idx === 0 && (
                          <div className="absolute -top-3 left-6 bg-indigo-600 text-white text-[10px] font-black px-3 py-1 rounded-full shadow-lg">
                            BEST PRICE
                          </div>
                        )}
                        <div className="flex justify-between items-start mb-4 pt-2">
                          <span className="text-xs font-black text-indigo-600 uppercase tracking-tighter">{item.platform}</span>
                          {getConfidenceBadge(item.confidence)}
                        </div>
                        <h4 className="text-sm font-bold text-slate-800 line-clamp-2 mb-6 group-hover:text-indigo-600 transition-colors">
                          {item.product.title}
                        </h4>
                        <div className="mt-auto">
                          <div className="text-2xl font-black text-slate-900 mb-4">{item.product.price}</div>
                          <a href={item.product.productUrl} className="block w-full text-center border-2 border-slate-100 text-slate-600 font-bold py-2.5 rounded-xl hover:bg-indigo-50 hover:border-indigo-100 hover:text-indigo-600 transition-all">
                            View Deal
                          </a>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* SIDEBAR */}
            <aside className="lg:col-span-1">
              <div className="bg-white rounded-2xl shadow-xl shadow-slate-200/50 border border-slate-100 p-6 sticky top-28">
                <h4 className="font-black text-slate-900 uppercase text-xs tracking-widest mb-6 border-b border-slate-50 pb-4">
                  Scanner Status
                </h4>
                <div className="space-y-4 mb-8">
                  {data.results?.map((res, i) => (
                    <div key={i} className="flex items-center justify-between">
                      <span className="text-sm font-bold text-slate-500 capitalize">{res.platform}</span>
                      <div className={`px-2 py-1 rounded-md text-[10px] font-black uppercase ${res.status === 'success' ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-50 text-slate-400'}`}>
                        {res.status === 'success' ? 'Ready' : 'Skipped'}
                      </div>
                    </div>
                  ))}
                </div>
                <div className="bg-indigo-600 rounded-xl p-4 text-white">
                    <p className="text-[10px] font-bold opacity-70 uppercase mb-1">Engine Score</p>
                    <p className="text-xl font-black">Optimal Result</p>
                </div>
              </div>
            </aside>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;